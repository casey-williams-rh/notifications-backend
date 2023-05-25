package com.redhat.cloud.notifications.processors.eventing;

import com.redhat.cloud.notifications.Base64Utils;
import com.redhat.cloud.notifications.DelayedThrower;
import com.redhat.cloud.notifications.config.FeatureFlipper;
import com.redhat.cloud.notifications.db.converters.MapConverter;
import com.redhat.cloud.notifications.models.BasicAuthentication;
import com.redhat.cloud.notifications.models.CamelProperties;
import com.redhat.cloud.notifications.models.Endpoint;
import com.redhat.cloud.notifications.models.Event;
import com.redhat.cloud.notifications.models.NotificationHistory;
import com.redhat.cloud.notifications.models.NotificationStatus;
import com.redhat.cloud.notifications.processors.EndpointTypeProcessor;
import com.redhat.cloud.notifications.routers.sources.SecretUtils;
import com.redhat.cloud.notifications.transformers.BaseTransformer;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.context.Context;
import io.quarkus.logging.Log;
import io.smallrye.reactive.messaging.TracingMetadata;
import io.smallrye.reactive.messaging.ce.CloudEventMetadata;
import io.smallrye.reactive.messaging.ce.OutgoingCloudEventMetadata;
import io.vertx.core.json.JsonObject;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Message;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.redhat.cloud.notifications.events.EndpointProcessor.DELAYED_EXCEPTION_MSG;
import static com.redhat.cloud.notifications.models.NotificationHistory.getHistoryStub;

@ApplicationScoped
public class EventingProcessor extends EndpointTypeProcessor {

    public static final String TOCAMEL_CHANNEL = "tocamel";
    public static final String PROCESSED_COUNTER_NAME = "processor.camel.processed";
    public static final String TOKEN_HEADER = "X-Insight-Token";
    public static final String NOTIF_METADATA_KEY = "notif-metadata";
    public static final String CLOUD_EVENT_TYPE_PREFIX = "com.redhat.console.notification.toCamel.";

    @Inject
    FeatureFlipper featureFlipper;

    @Inject
    BaseTransformer baseTransformer;

    @Inject
    @Channel(TOCAMEL_CHANNEL)
    Emitter<String> emitter;

    @Inject
    MeterRegistry registry;

    @Inject
    SecretUtils secretUtils;

    @Override
    public void process(Event event, List<Endpoint> endpoints) {
        if (featureFlipper.isEmailsOnlyMode()) {
            Log.warn("Skipping event processing because Notifications is running in emails only mode");
            return;
        }
        DelayedThrower.throwEventually(DELAYED_EXCEPTION_MSG, accumulator -> {
            for (Endpoint endpoint : endpoints) {
                try {
                    process(event, endpoint);
                } catch (Exception e) {
                    accumulator.add(e);
                }
            }
        });
    }

    private void process(Event event, Endpoint endpoint) {
        registry.counter(PROCESSED_COUNTER_NAME, "subType", endpoint.getSubType()).increment();

        String originalEventId = getOriginalEventId(event);
        UUID historyId = UUID.randomUUID();

        Log.infof("Sending CloudEvent [orgId=%s, integration=%s, historyId=%s, originalEventId=%s]",
                endpoint.getOrgId(), endpoint.getName(), historyId, originalEventId);

        long startTime = System.currentTimeMillis();
        Message<String> message = buildMessage(event, endpoint, originalEventId, historyId);
        emitter.send(message);
        long invocationTime = System.currentTimeMillis() - startTime;

        createHistoryEntry(event, endpoint, historyId, invocationTime);
    }

    private static String getOriginalEventId(Event event) {
        String originalEventId = "-not provided-";
        if (event.getId() != null) {
            originalEventId = event.getId().toString();
        }
        return originalEventId;
    }

    private Message<String> buildMessage(Event event, Endpoint endpoint, String originalEventId, UUID historyId) {
        JsonObject payload = buildPayload(event, endpoint, originalEventId);

        CloudEventMetadata<String> cloudEventMetadata = buildCloudEventMetadata(endpoint, historyId);
        TracingMetadata tracingMetadata = TracingMetadata.withPrevious(Context.current());

        return Message.of(payload.encode())
                .addMetadata(cloudEventMetadata)
                .addMetadata(tracingMetadata);
    }

    private JsonObject buildPayload(Event event, Endpoint endpoint, String originalEventId) {
        CamelProperties properties = endpoint.getProperties(CamelProperties.class);

        JsonObject metaData = new JsonObject();
        metaData.put("trustAll", String.valueOf(properties.getDisableSslVerification()));
        metaData.put("url", properties.getUrl());
        metaData.put("type", endpoint.getSubType());
        metaData.put("extras", new MapConverter().convertToDatabaseColumn(properties.getExtras()));
        metaData.put("_originalId", originalEventId);

        if (featureFlipper.isSourcesUsedAsSecretsBackend()) {
            // Get the basic authentication and secret token secrets from Sources.
            secretUtils.loadSecretsForEndpoint(endpoint);
        }
        getSecretToken(properties).ifPresent(secretToken -> metaData.put(TOKEN_HEADER, secretToken));
        getBasicAuth(properties).ifPresent(basicAuth -> metaData.put("basicAuth", basicAuth));

        final JsonObject payload = baseTransformer.toJsonObject(event);
        payload.put(NOTIF_METADATA_KEY, metaData);

        return payload;
    }

    private static Optional<String> getSecretToken(CamelProperties properties) {
        if (properties.getSecretToken() == null || properties.getSecretToken().isBlank()) {
            return Optional.empty();
        } else {
            return Optional.of(properties.getSecretToken());
        }
    }

    private static Optional<String> getBasicAuth(CamelProperties properties) {
        BasicAuthentication basicAuthentication = properties.getBasicAuthentication();
        if (basicAuthentication == null || basicAuthentication.getUsername() == null || basicAuthentication.getPassword() == null) {
            return Optional.empty();
        } else {
            String credentials = basicAuthentication.getUsername() + ":" + basicAuthentication.getPassword();
            return Optional.of(Base64Utils.encode(credentials));
        }
    }

    private static OutgoingCloudEventMetadata<String> buildCloudEventMetadata(Endpoint endpoint, UUID historyId) {
        return OutgoingCloudEventMetadata.<String>builder()
                .withId(historyId.toString())
                .withType(CLOUD_EVENT_TYPE_PREFIX + endpoint.getSubType())
                .build();
    }

    private void createHistoryEntry(Event event, Endpoint endpoint, UUID historyId, long invocationTime) {
        // We only create a basic stub. The FromCamel filler will update it later
        NotificationHistory history = getHistoryStub(endpoint, event, invocationTime, historyId);
        history.setStatus(NotificationStatus.PROCESSING);
        persistNotificationHistory(history);
    }
}
