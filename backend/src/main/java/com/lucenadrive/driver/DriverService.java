package com.lucenadrive.driver;

import com.lucenadrive.common.crypto.FieldEncryptor;
import com.lucenadrive.common.error.ApiException;
import com.lucenadrive.common.error.ErrorCode;
import com.lucenadrive.common.web.Ids;
import com.lucenadrive.fare.VehicleCategory;
import com.lucenadrive.geo.City;
import com.lucenadrive.integration.ObjectStorage;
import com.lucenadrive.persistence.Collections;
import com.lucenadrive.persistence.DocStore;
import com.lucenadrive.persistence.Query;
import com.lucenadrive.security.AuthUser;
import com.lucenadrive.security.Role;
import com.lucenadrive.user.AccountService;
import com.lucenadrive.user.IdentityRegistrationService;
import com.lucenadrive.user.User;
import com.lucenadrive.user.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class DriverService {

    static final Set<String> ALLOWED_TYPES = Set.of("application/pdf", "image/jpeg", "image/png", "image/webp");
    static final long MAX_BYTES = 10L * 1024 * 1024;

    public record DriverRegistration(IdentityRegistrationService.IdentityData identity, String cnhNumber,
                                     String cnhCategory, LocalDate cnhExpiresOn, boolean cnhEar, String cityId) {
    }

    public record VehicleInput(String make, String model, int year, String color, String plate,
                               VehicleCategory category, LocalDate crlvExpiresOn) {
    }

    public record DocumentInput(DocumentType type, String storagePath, LocalDate expiresOn, String vehicleId) {
    }

    public record DriverOverview(DriverProfile profile, List<Vehicle> vehicles, boolean online,
                                 String currentRideId, List<String> missingRequirements) {
    }

    private final DocStore store;
    private final IdentityRegistrationService identity;
    private final FieldEncryptor encryptor;
    private final UserRepository users;
    private final ObjectProvider<ObjectStorage> storage;
    private final Clock clock;

    public DriverService(DocStore store, IdentityRegistrationService identity, FieldEncryptor encryptor,
                         UserRepository users, ObjectProvider<ObjectStorage> storage, Clock clock) {
        this.store = store;
        this.identity = identity;
        this.encryptor = encryptor;
        this.users = users;
        this.storage = storage;
        this.clock = clock;
    }

    public static List<VehicleCategory> servedCategories(VehicleCategory vehicleCategory) {
        return switch (vehicleCategory) {
            case EXECUTIVO -> List.of(VehicleCategory.EXECUTIVO, VehicleCategory.CONFORTO, VehicleCategory.ESSENCIAL);
            case CONFORTO -> List.of(VehicleCategory.CONFORTO, VehicleCategory.ESSENCIAL);
            case ESSENCIAL -> List.of(VehicleCategory.ESSENCIAL);
        };
    }

    public DriverProfile register(AuthUser auth, DriverRegistration reg) {
        String cnh = reg.cnhNumber() == null ? "" : reg.cnhNumber().replaceAll("\\D", "");
        if (cnh.length() != 11) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Número de CNH deve ter 11 dígitos.");
        }
        LocalDate today = LocalDate.now(clock.withZone(IdentityRegistrationService.BR));
        if (reg.cnhExpiresOn().isBefore(today)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "CNH vencida.");
        }
        if (!reg.cnhEar()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "A CNH precisa conter a observação EAR (exerce atividade remunerada).");
        }
        return store.transaction(tx -> {
            City city = tx.get(Collections.CITIES, reg.cityId(), City.class).filter(City::active)
                    .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED, "Cidade não atendida."));
            if (tx.get(Collections.DRIVER_PROFILES, auth.uid(), DriverProfile.class).isPresent()) {
                throw new ApiException(ErrorCode.ALREADY_REGISTERED, "Cadastro de motorista já existe.");
            }
            identity.registerInTx(tx, auth, reg.identity(), Role.DRIVER);
            Instant now = Instant.now(clock);
            DriverProfile profile = new DriverProfile(auth.uid(), DriverStatus.INCOMPLETE,
                    "*******" + cnh.substring(7), reg.cnhCategory(), reg.cnhExpiresOn().toString(), true,
                    List.of(), city.id(), null, 0, 0, 0, new HashMap<>(), 0, 0, 0, null, null, null, null, now, now);
            tx.set(Collections.DRIVER_PROFILES, auth.uid(), profile);
            tx.set(Collections.DRIVER_PRIVATE, auth.uid(), new DriverPrivate(auth.uid(), encryptor.encrypt(cnh), now));
            return profile;
        });
    }

    public DriverProfile requireProfile(String uid) {
        return store.get(Collections.DRIVER_PROFILES, uid, DriverProfile.class)
                .orElseThrow(() -> new ApiException(ErrorCode.PROFILE_NOT_FOUND, "Cadastro de motorista não encontrado."));
    }

    public List<Vehicle> vehicles(String driverId) {
        return store.query(Query.of(Collections.VEHICLES).eq("driverId", driverId), Vehicle.class);
    }

    public DriverOverview overview(String uid) {
        DriverProfile p = requireProfile(uid);
        List<Vehicle> vs = vehicles(uid);
        Optional<DriverLocation> loc = store.get(Collections.DRIVER_LOCATIONS, uid, DriverLocation.class);
        return new DriverOverview(p, vs, loc.map(DriverLocation::online).orElse(false),
                loc.map(DriverLocation::currentRideId).orElse(null), missingRequirements(p, vs));
    }

    public Vehicle addVehicle(AuthUser auth, VehicleInput in) {
        String plate = Plates.normalize(in.plate())
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED, "Placa inválida."));
        int currentYear = LocalDate.now(clock).getYear();
        if (in.year() < in.category().minVehicleYear() || in.year() > currentYear + 1) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Ano do veículo não atende à categoria "
                    + in.category().displayName() + " (mínimo " + in.category().minVehicleYear() + ").");
        }
        String plateHash = encryptor.indexHash("plate:" + plate);
        return store.transaction(tx -> {
            DriverProfile p = tx.get(Collections.DRIVER_PROFILES, auth.uid(), DriverProfile.class)
                    .orElseThrow(() -> new ApiException(ErrorCode.PROFILE_NOT_FOUND, "Cadastro de motorista não encontrado."));
            if (tx.get(Collections.PLATE_INDEX, plateHash, PlateIndex.class).isPresent()) {
                throw new ApiException(ErrorCode.PLATE_ALREADY_REGISTERED, "Placa já cadastrada.");
            }
            Instant now = Instant.now(clock);
            Vehicle v = new Vehicle(Ids.newId(), auth.uid(), in.make().trim(), in.model().trim(), in.year(),
                    in.color().trim(), plate, in.category(), Vehicle.Status.PENDING_REVIEW,
                    in.crlvExpiresOn() == null ? null : in.crlvExpiresOn().toString(), null, now, now);
            tx.create(Collections.PLATE_INDEX, plateHash, new PlateIndex(v.id(), auth.uid()));
            tx.set(Collections.VEHICLES, v.id(), v);
            if (p.activeVehicleId() == null) {
                tx.set(Collections.DRIVER_PROFILES, p.id(), p.toBuilder().activeVehicleId(v.id()).updatedAt(now).build());
            }
            return v;
        });
    }

    public DriverProfile setActiveVehicle(AuthUser auth, String vehicleId) {
        return store.transaction(tx -> {
            DriverProfile p = tx.get(Collections.DRIVER_PROFILES, auth.uid(), DriverProfile.class)
                    .orElseThrow(() -> ApiException.notFound("Motorista"));
            Vehicle v = tx.get(Collections.VEHICLES, vehicleId, Vehicle.class)
                    .filter(x -> x.driverId().equals(auth.uid()))
                    .orElseThrow(() -> ApiException.notFound("Veículo"));
            DriverProfile next = p.toBuilder().activeVehicleId(v.id()).updatedAt(Instant.now(clock)).build();
            tx.set(Collections.DRIVER_PROFILES, p.id(), next);
            return next;
        });
    }

    /**
     * Registra um documento já enviado pelo cliente ao Storage. O backend confere caminho, existência,
     * tipo e tamanho do arquivo antes de aceitar.
     */
    public DriverProfile attachDocument(AuthUser auth, DocumentInput in) {
        String prefix = "driverDocuments/" + auth.uid() + "/";
        if (in.storagePath() == null || !in.storagePath().startsWith(prefix) || in.storagePath().contains("..")) {
            throw ApiException.forbidden("Caminho de arquivo inválido.");
        }
        ObjectStorage os = storage.getIfAvailable();
        if (os == null) {
            throw new ApiException(ErrorCode.INTEGRATION_NOT_CONFIGURED, "Armazenamento de arquivos indisponível.");
        }
        ObjectStorage.ObjectInfo info = os.stat(in.storagePath())
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED, "Arquivo não encontrado no armazenamento."));
        if (!ALLOWED_TYPES.contains(info.contentType()) || info.sizeBytes() > MAX_BYTES) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Arquivo deve ser PDF/JPEG/PNG/WebP de até 10 MB.");
        }
        if (in.type().vehicleDocument() && in.vehicleId() == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Informe o veículo do documento.");
        }
        return store.transaction(tx -> {
            DriverProfile p = tx.get(Collections.DRIVER_PROFILES, auth.uid(), DriverProfile.class)
                    .orElseThrow(() -> ApiException.notFound("Motorista"));
            if (in.vehicleId() != null) {
                tx.get(Collections.VEHICLES, in.vehicleId(), Vehicle.class)
                        .filter(v -> v.driverId().equals(auth.uid()))
                        .orElseThrow(() -> ApiException.notFound("Veículo"));
            }
            Instant now = Instant.now(clock);
            List<DriverDocument> docs = new ArrayList<>(p.documents() == null ? List.of() : p.documents());
            docs.removeIf(d -> d.type() == in.type() && java.util.Objects.equals(d.vehicleId(), in.vehicleId()));
            docs.add(new DriverDocument(in.type(), in.storagePath(), info.contentType(), info.sizeBytes(),
                    DriverDocument.Status.PENDING, in.expiresOn() == null ? null : in.expiresOn().toString(),
                    in.vehicleId(), now, null, null, null));
            DriverProfile.Builder b = p.toBuilder().documents(docs).updatedAt(now);
            if (in.type() == DocumentType.PROFILE_PHOTO) {
                users.findById(auth.uid()).ifPresent(u -> tx.set(Collections.USERS, u.id(),
                        u.withContact(u.fullName(), u.phoneNumber(), in.storagePath(), now)));
            }
            // Novo documento após aprovação exige nova análise apenas se o motorista estava com docs vencidos.
            if (p.status() == DriverStatus.DOCUMENTS_EXPIRED) {
                b.status(DriverStatus.PENDING_REVIEW, "Documentação reenviada");
            }
            DriverProfile next = b.build();
            tx.set(Collections.DRIVER_PROFILES, p.id(), next);
            return next;
        });
    }

    public DriverProfile submitForReview(AuthUser auth) {
        return store.transaction(tx -> {
            DriverProfile p = tx.get(Collections.DRIVER_PROFILES, auth.uid(), DriverProfile.class)
                    .orElseThrow(() -> ApiException.notFound("Motorista"));
            if (p.status() != DriverStatus.INCOMPLETE && p.status() != DriverStatus.REJECTED) {
                throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "Cadastro já enviado para análise.");
            }
            List<Vehicle> vs = tx.query(Query.of(Collections.VEHICLES).eq("driverId", auth.uid()), Vehicle.class);
            List<String> missing = missingRequirements(p, vs);
            if (!missing.isEmpty()) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "Cadastro incompleto.",
                        java.util.Map.of("missing", missing));
            }
            Instant now = Instant.now(clock);
            DriverProfile next = p.toBuilder().status(DriverStatus.PENDING_REVIEW, null).submittedAt(now).updatedAt(now).build();
            tx.set(Collections.DRIVER_PROFILES, p.id(), next);
            return next;
        });
    }

    static List<String> missingRequirements(DriverProfile p, List<Vehicle> vehicles) {
        List<String> missing = new ArrayList<>();
        Set<DocumentType> present = EnumSet.noneOf(DocumentType.class);
        if (p.documents() != null) {
            p.documents().stream().filter(d -> d.status() != DriverDocument.Status.REJECTED)
                    .forEach(d -> present.add(d.type()));
        }
        for (DocumentType t : DocumentType.values()) {
            if (t.requiredForDriver() && !present.contains(t)) {
                missing.add("DOCUMENT_" + t.name());
            }
        }
        if (vehicles.isEmpty()) {
            missing.add("VEHICLE");
        } else if (p.activeVehicleId() != null) {
            boolean hasCrlv = p.documents() != null && p.documents().stream().anyMatch(d -> d.type() == DocumentType.CRLV
                    && p.activeVehicleId().equals(d.vehicleId()) && d.status() != DriverDocument.Status.REJECTED);
            if (!hasCrlv) {
                missing.add("DOCUMENT_CRLV");
            }
        }
        return missing;
    }

    /** Fica online: exige motorista e veículo aprovados, conta ativa e documentos válidos. */
    public DriverLocation goOnline(AuthUser auth, double lat, double lng) {
        User u = users.require(auth.uid());
        AccountService.ensureActive(u);
        return store.transaction(tx -> {
            DriverProfile p = tx.get(Collections.DRIVER_PROFILES, auth.uid(), DriverProfile.class)
                    .orElseThrow(() -> ApiException.notFound("Motorista"));
            if (p.status() != DriverStatus.APPROVED) {
                throw new ApiException(ErrorCode.DRIVER_NOT_ELIGIBLE, "Cadastro de motorista não está aprovado.");
            }
            LocalDate today = LocalDate.now(clock.withZone(IdentityRegistrationService.BR));
            if (p.cnhExpiresOn() != null && LocalDate.parse(p.cnhExpiresOn()).isBefore(today)) {
                throw new ApiException(ErrorCode.DRIVER_NOT_ELIGIBLE, "CNH vencida. Atualize sua documentação.");
            }
            Vehicle v = p.activeVehicleId() == null ? null
                    : tx.get(Collections.VEHICLES, p.activeVehicleId(), Vehicle.class).orElse(null);
            if (v == null || v.status() != Vehicle.Status.APPROVED) {
                throw new ApiException(ErrorCode.DRIVER_NOT_ELIGIBLE, "Selecione um veículo aprovado.");
            }
            Optional<DriverLocation> existing = tx.get(Collections.DRIVER_LOCATIONS, auth.uid(), DriverLocation.class);
            Instant now = Instant.now(clock);
            String currentRide = existing.map(DriverLocation::currentRideId).orElse(null);
            DriverLocation loc = new DriverLocation(auth.uid(), true, currentRide == null, p.cityId(),
                    servedCategories(v.category()), v.id(), lat, lng, null, null, null, currentRide, now, now);
            tx.set(Collections.DRIVER_LOCATIONS, auth.uid(), loc);
            return loc;
        });
    }

    public void goOffline(AuthUser auth) {
        store.transaction(tx -> {
            tx.get(Collections.DRIVER_LOCATIONS, auth.uid(), DriverLocation.class).ifPresent(loc -> {
                if (loc.currentRideId() != null) {
                    throw new ApiException(ErrorCode.ACTIVE_RIDE_EXISTS, "Finalize a corrida atual antes de ficar offline.");
                }
                tx.set(Collections.DRIVER_LOCATIONS, auth.uid(), loc.withOnline(false, Instant.now(clock)));
            });
            return null;
        });
    }

    public record PlateIndex(String vehicleId, String driverId) {
    }
}
