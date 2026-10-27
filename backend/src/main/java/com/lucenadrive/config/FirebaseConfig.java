package com.lucenadrive.config;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.firestore.Firestore;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.cloud.FirestoreClient;
import com.google.firebase.cloud.StorageClient;
import com.google.firebase.messaging.FirebaseMessaging;
import com.lucenadrive.integration.firebase.FirebaseAuthAdmin;
import com.lucenadrive.integration.firebase.FirebaseIdTokenVerifier;
import com.lucenadrive.integration.firebase.FirebaseObjectStorage;
import com.lucenadrive.integration.firebase.FcmPushSender;
import com.lucenadrive.persistence.DocStore;
import com.lucenadrive.persistence.FirestoreDocStore;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Date;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Inicialização do Firebase Admin SDK. Com emuladores, as variáveis FIRESTORE_EMULATOR_HOST,
 * FIREBASE_AUTH_EMULATOR_HOST e FIREBASE_STORAGE_EMULATOR_HOST direcionam o SDK ao Emulator Suite.
 */
@Configuration
@ConditionalOnProperty(prefix = "lucena.firebase", name = "enabled", havingValue = "true", matchIfMissing = true)
public class FirebaseConfig {

    private static final Logger log = LoggerFactory.getLogger(FirebaseConfig.class);

    @Bean
    public FirebaseApp firebaseApp(LucenaProperties props) throws IOException {
        LucenaProperties.Firebase fb = props.firebase();
        if (fb.projectId() == null || fb.projectId().isBlank()) {
            throw new IllegalStateException("lucena.firebase.project-id (FIREBASE_PROJECT_ID) é obrigatório.");
        }
        GoogleCredentials credentials;
        if (fb.emulators()) {
            log.warn("Firebase em modo EMULADOR (projeto {}). Não use em produção.", fb.projectId());
            credentials = GoogleCredentials.create(new AccessToken("emulator-owner", new Date(Long.MAX_VALUE)));
        } else if (fb.credentialsPath() != null && !fb.credentialsPath().isBlank()) {
            try (InputStream in = new FileInputStream(fb.credentialsPath())) {
                credentials = GoogleCredentials.fromStream(in);
            }
        } else {
            credentials = GoogleCredentials.getApplicationDefault();
        }
        FirebaseOptions.Builder options = FirebaseOptions.builder()
                .setCredentials(credentials)
                .setProjectId(fb.projectId());
        if (fb.storageBucket() != null && !fb.storageBucket().isBlank()) {
            options.setStorageBucket(fb.storageBucket());
        }
        return FirebaseApp.getApps().stream().filter(a -> a.getName().equals(FirebaseApp.DEFAULT_APP_NAME))
                .findFirst().orElseGet(() -> FirebaseApp.initializeApp(options.build()));
    }

    @Bean
    public Firestore firestore(FirebaseApp app) {
        return FirestoreClient.getFirestore(app);
    }

    @Bean
    public DocStore docStore(Firestore firestore) {
        return new FirestoreDocStore(firestore);
    }

    @Bean
    public FirebaseIdTokenVerifier idTokenVerifier(FirebaseApp app, LucenaProperties props) {
        return new FirebaseIdTokenVerifier(FirebaseAuth.getInstance(app), !props.firebase().emulators());
    }

    @Bean
    public FirebaseAuthAdmin authAdmin(FirebaseApp app) {
        return new FirebaseAuthAdmin(FirebaseAuth.getInstance(app));
    }

    @Bean
    public FirebaseObjectStorage objectStorage(FirebaseApp app, LucenaProperties props) {
        String bucket = props.firebase().storageBucket();
        String emulator = System.getenv("FIREBASE_STORAGE_EMULATOR_HOST");
        if (props.firebase().emulators() && emulator != null && !emulator.isBlank()) {
            com.google.cloud.storage.Storage storage = com.google.cloud.storage.StorageOptions.newBuilder()
                    .setProjectId(props.firebase().projectId())
                    .setHost("http://" + emulator)
                    .setCredentials(com.google.cloud.NoCredentials.getInstance())
                    .build().getService();
            return new FirebaseObjectStorage(storage, bucket);
        }
        return new FirebaseObjectStorage(StorageClient.getInstance(app).bucket().getStorage(), bucket);
    }

    @Bean
    public FcmPushSender pushSender(FirebaseApp app) {
        return new FcmPushSender(FirebaseMessaging.getInstance(app));
    }
}
