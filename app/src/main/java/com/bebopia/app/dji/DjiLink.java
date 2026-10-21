package com.bebopia.app.dji;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.util.Log;

import dji.common.battery.BatteryState;
import dji.common.error.DJIError;
import dji.common.error.DJISDKError;
import dji.common.flightcontroller.FlightControllerState;
import dji.common.flightcontroller.virtualstick.FlightControlData;
import dji.common.flightcontroller.virtualstick.FlightCoordinateSystem;
import dji.common.flightcontroller.virtualstick.RollPitchControlMode;
import dji.common.flightcontroller.virtualstick.VerticalControlMode;
import dji.common.flightcontroller.virtualstick.YawControlMode;
import dji.common.camera.SettingsDefinitions;
import dji.common.gimbal.Rotation;
import dji.common.gimbal.RotationMode;
import dji.common.util.CommonCallbacks;
import dji.sdk.base.BaseComponent;
import dji.sdk.base.BaseProduct;
import dji.sdk.camera.VideoFeeder;
import dji.sdk.codec.DJICodecManager;
import dji.sdk.flightcontroller.FlightController;
import dji.sdk.gimbal.Gimbal;
import dji.sdk.products.Aircraft;
import dji.sdk.sdkmanager.DJISDKInitEvent;
import dji.sdk.sdkmanager.DJISDKManager;

/**
 * Tout ce qui touche au kit DJI (Mobile SDK 4.18), écrit en Java comme les exemples officiels.
 * Le reste de l'application ne voit que des méthodes simples.
 */
public class DjiLink {
    private static final String TAG = "DjiLink";

    /** Messages pour le pilote, et changements de connexion. */
    public interface Listener {
        void onStatus(String message);
        void onProductChanged(boolean connected);
        /** Le pilote bouge un manche de la radiocommande. */
        default void onPilotSticks() {}
    }

    /** Résultat d'une commande : null si tout va bien, sinon le message d'erreur. */
    public interface Done {
        void done(String error);
    }


    private volatile boolean registered = false;
    private volatile Listener listener;
    private volatile Integer battery = null;
    private volatile float altitude = 0f;
    private volatile boolean flying = false;
    private volatile boolean landingConfirmationNeeded = false;
    private volatile String flightMode = "";
    private volatile boolean imuPreheating = false;
    // Diagnostic avant décollage
    private volatile boolean tofUsed = false, tofError = false;
    private volatile float tofHeight = 0;
    private volatile java.util.List<String[]> djiMessages = new java.util.ArrayList<>();
    private volatile String compassCal = "NOT_CALIBRATING";
    private volatile String gps = "?";
    private volatile int satellites = 0;
    private volatile boolean vps = false;
    private volatile boolean motorsOn = false;
    private volatile boolean lowBattery = false;
    private volatile double heading = 0;
    private volatile double attPitch = 0, attRoll = 0;
    private volatile float vx = 0, vy = 0, vz = 0;
    private volatile int flightTime = 0;
    private volatile boolean homeSet = false;
    private volatile double lat = Double.NaN, lon = Double.NaN, homeLat = Double.NaN, homeLon = Double.NaN;
    private volatile float gimbalPitchDeg = 0;
    private volatile long lastFrameAt = 0L;
    private volatile DJICodecManager codec;
    private volatile VideoFeeder.VideoFeed listenedFeed;
    private volatile long packets = 0;
    private volatile long packetsAtCheck = 0;
    private volatile long checkAt = 0;
    private volatile double packetRate = 0;

    /** Un seul abonnement au flux vidéo, refait à chaque changement côté drone (le flux peut être recréé). */
    private final VideoFeeder.VideoDataListener videoListener = new VideoFeeder.VideoDataListener() {
        @Override
        public void onReceive(byte[] buffer, int size) {
            lastFrameAt = System.currentTimeMillis();
            packets++;
            DJICodecManager c = codec;
            if (c != null) c.sendDataToDecoder(buffer, size);
        }
    };

    public boolean isRegistered() { return registered; }
    public Integer battery() { return battery; }
    public float altitude() { return altitude; }
    public boolean flying() { return flying; }
    public boolean landingConfirmationNeeded() { return landingConfirmationNeeded; }
    public String flightMode() { return flightMode; }
    /** Télémétrie pour le cockpit du casque. */
    public java.util.Map<String, Object> telemetry() {
        java.util.HashMap<String, Object> m = new java.util.HashMap<>();
        m.put("heading", heading);
        m.put("pitch", attPitch);
        m.put("roll", attRoll);
        m.put("hSpeed", Math.hypot(vx, vy));
        m.put("vSpeed", -vz); // le kit DJI compte vers le bas
        m.put("flightTime", flightTime);
        m.put("gimbal", gimbalPitchDeg);
        m.put("gps", gps);
        m.put("sats", satellites);
        m.put("flightMode", flightMode);
        m.put("vps", vps);
        String[] bs = belowState();
        m.put("below", bs[0]);
        m.put("imuPreheating", imuPreheating);
        m.put("lowBattery", lowBattery);
        m.put("rec", recording);
        int q = signalQuality();
        if (q >= 0) m.put("signal", q);
        // Position GPS (pour la mini-carte du casque)
        if (!Double.isNaN(lat) && Math.abs(lat) > 0.0001 && satellites >= 4) { m.put("lat", lat); m.put("lon", lon); }
        if (homeSet && !Double.isNaN(homeLat) && Math.abs(homeLat) > 0.0001) { m.put("homeLat", homeLat); m.put("homeLon", homeLon); }
        // Distance et cap du point de départ par GPS, si le point de départ est enregistré
        if (homeSet && !Double.isNaN(lat) && !Double.isNaN(homeLat) && satellites >= 6 && Math.abs(lat) > 0.0001) {
            double r = 6371000, p1 = Math.toRadians(lat), p2 = Math.toRadians(homeLat);
            double dp = p2 - p1, dl = Math.toRadians(homeLon - lon);
            double h = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
            m.put("homeDist", 2 * r * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h)));
            double y = Math.sin(dl) * Math.cos(p2), x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
            m.put("homeBearing", Math.toDegrees(Math.atan2(y, x)));
            m.put("homeSource", "GPS");
        }
        return m;
    }

    /**
     * État réel des capteurs du dessous. Le drone ne dit qu'une chose : « je m'en sers en ce moment pour tenir ma position ».
     * C'est faux au sol (normal), quand le GPS suffit (normal dehors), et hors de leur portée (sous ~0,5 m ou au-dessus de ~10 m).
     * Renvoie {code, texte} : sol, actifs, gps, trop_haut, trop_bas, inactifs.
     */
    public String[] belowState() {
        boolean used = vps || tofUsed || flightMode.toUpperCase(java.util.Locale.ROOT).contains("OPTI");
        if (!flying) return new String[] { "sol", "au sol : le drone ne s'en sert qu'une fois en l'air (vérifié après le décollage)" };
        if (used) return new String[] { "actifs", "actifs : le drone tient sa position grâce à eux" };
        if (altitude > 9) return new String[] { "trop_haut", String.format(java.util.Locale.FRANCE, "pas utilisés à %.0f m : trop haut pour eux (portée ~10 m), normal", altitude) };
        if (altitude < 0.4) return new String[] { "trop_bas", "pas encore utilisés : trop près du sol" };
        if (satellites >= 8) return new String[] { "gps", "pas utilisés : le GPS suffit (normal dehors)" };
        return new String[] { "inactifs", "INACTIFS alors que le drone vole sans GPS : il risque de dériver" };
    }

    /** Cap du drone (boussole), en degrés de -180 à 180. */
    public double heading() { return heading; }

    /** Le drone ne tient pas sa position (mode ATTI, ou ni caméra du dessous ni mode optique annoncé). */
    public boolean positionWeak() {
        String m = flightMode.toUpperCase(java.util.Locale.ROOT);
        if (m.contains("ATTI")) return true;
        if (m.contains("OPTI") || m.contains("GPS")) return false;
        return !vps;
    }

    public String flightModeName() { return flightMode.isEmpty() ? "?" : flightMode; }

    /** Le drone tient sa position avec la caméra du dessous. */
    public boolean vpsUsed() { return vps; }

    /** Hauteur mesurée par le capteur du dessous (m), -1 si non utilisée. */
    public double tofHeight() { return tofUsed && !tofError ? tofHeight : -1; }

    /** Inclinaison de la nacelle (°, négatif = vers le bas). */
    public double gimbalPitchNow() { return gimbalPitchDeg; }

    /** Position GPS fiable (assez de satellites) : {lat, lon}, sinon null. */
    public double[] position() {
        if (Double.isNaN(lat) || Math.abs(lat) < 0.0001 || satellites < 6) return null;
        return new double[] { lat, lon };
    }

    public double[] home() {
        if (!homeSet || Double.isNaN(homeLat) || Math.abs(homeLat) < 0.0001) return null;
        return new double[] { homeLat, homeLon };
    }

    private volatile int upQuality = -1, downQuality = -1;
    private volatile long qualityAt = 0;

    /** Qualité de la liaison (0 à 100, la plus faible des deux voies), -1 si inconnue. */
    public int signalQuality() {
        if (System.currentTimeMillis() - qualityAt > 5000) return -1;
        if (upQuality < 0) return downQuality;
        if (downQuality < 0) return upQuality;
        return Math.min(upQuality, downQuality);
    }

    private volatile boolean recording = false;
    public boolean recording() { return recording; }

    /** Enregistrement vidéo sur la carte SD du drone. */
    public void record(final boolean start, final Done d) {
        BaseProduct p = DJISDKManager.getInstance().getProduct();
        final dji.sdk.camera.Camera cam = p == null ? null : p.getCamera();
        if (cam == null) {
            if (d != null) d.done("caméra indisponible");
            return;
        }
        if (!start) {
            cam.stopRecordVideo(cb(new Done() {
                @Override
                public void done(String e) {
                    if (e == null) recording = false;
                    if (d != null) d.done(e);
                }
            }));
            return;
        }
        final CommonCallbacks.CompletionCallback go = new CommonCallbacks.CompletionCallback() {
            @Override
            public void onResult(DJIError e0) {
                cam.startRecordVideo(cb(new Done() {
                    @Override
                    public void done(String e) {
                        if (e == null) recording = true;
                        if (d != null) d.done(e);
                    }
                }));
            }
        };
        // Mavic Mini et drones récents : « flat mode » ; anciens : mode caméra.
        cam.setFlatMode(SettingsDefinitions.FlatCameraMode.VIDEO_NORMAL, new CommonCallbacks.CompletionCallback() {
            @Override
            public void onResult(DJIError e) {
                if (e == null) go.onResult(null);
                else cam.setMode(SettingsDefinitions.CameraMode.RECORD_VIDEO, go);
            }
        });
    }

    /** Nacelle vers un angle, sans attendre la réponse (pour suivre la tête en continu). */
    public void gimbalPitchFast(float degrees) {
        Aircraft a = aircraft();
        Gimbal g = a == null ? null : a.getGimbal();
        if (g == null) return;
        g.rotate(new Rotation.Builder().mode(RotationMode.ABSOLUTE_ANGLE).pitch(degrees).time(0.1).build(), null);
    }

    public boolean hasRecentFrame() { return System.currentTimeMillis() - lastFrameAt < 1500; }

    /** État du drone en clair, pour comprendre un refus (décollage…). */
    public String diagnostics() {
        StringBuilder b = new StringBuilder();
        b.append("mode de vol ").append(flightMode.isEmpty() ? "?" : flightMode);
        b.append(", GPS ").append(gps).append(" (").append(satellites).append(" satellites)");
        b.append(", capteurs du dessous ").append(belowState()[1]);
        if (imuPreheating) b.append(", centrale inertielle en préchauffage (attends 1 à 2 min)");
        if (motorsOn) b.append(", moteurs allumés");
        if (lowBattery) b.append(", batterie faible");
        try {
            FlightController f = fc();
            if (f != null && f.getCompass() != null) {
                if (f.getCompass().hasError()) b.append(", ERREUR BOUSSOLE (calibre-la dans DJI Fly ou éloigne le drone du métal)");
                if (f.getCompass().isCalibrating()) b.append(", boussole en calibration");
            }
            if (f != null && !f.isVirtualStickControlModeAvailable()) b.append(", pilotage par l'app indisponible pour l'instant");
        } catch (Throwable ignored) { }
        if ("inactifs".equals(belowState()[0])) b.append(". Le drone ne voit ni le GPS ni le sol : ouvre 🔧 Diagnostic pour comprendre");
        for (String[] m : djiMessages) b.append(". Drone : ").append(m[0]).append(m[1].isEmpty() ? "" : " (" + m[1] + ")");
        return b.toString();
    }

    // ---- retour au départ géré par le drone, photo, réglages caméra, médias --------------------
    /** Retour au départ par le drone lui-même (GPS) : il rentre et se pose, même si la liaison coupe. */
    public void goHome(boolean start, Done d) {
        FlightController f = fc();
        if (noFc(f, d)) return;
        if (start) f.startGoHome(cb(d)); else f.cancelGoHome(cb(d));
    }

    public boolean goingHome() {
        String m = flightMode.toUpperCase(java.util.Locale.ROOT).replace(" ", "").replace("_", "");
        return m.contains("GOHOME") || m.contains("RTH");
    }

    /** Si la liaison est perdue : le drone rentre seul, à [heightM] mètres (20 à 120). */
    public void failsafeGoHome(final int heightM, final Done d) {
        final FlightController f = fc();
        if (noFc(f, d)) return;
        f.setConnectionFailSafeBehavior(dji.common.flightcontroller.ConnectionFailSafeBehavior.GO_HOME, new CommonCallbacks.CompletionCallback() {
            @Override
            public void onResult(DJIError e1) {
                f.setGoHomeHeightInMeters(Math.max(20, Math.min(120, heightM)), cb(new Done() {
                    @Override
                    public void done(String e2) { if (d != null) d.done(e1 != null ? e1.getDescription() : e2); }
                }));
            }
        });
    }

    private dji.sdk.camera.Camera camera() {
        BaseProduct p = DJISDKManager.getInstance().getProduct();
        return p == null ? null : p.getCamera();
    }

    /** Photo sur la carte du drone, puis retour en mode vidéo. */
    public void shootPhoto(final Done d) {
        final dji.sdk.camera.Camera cam = camera();
        if (cam == null) { if (d != null) d.done("caméra indisponible"); return; }
        final CommonCallbacks.CompletionCallback shoot = new CommonCallbacks.CompletionCallback() {
            @Override
            public void onResult(DJIError e0) {
                cam.startShootPhoto(cb(new Done() {
                    @Override
                    public void done(String e) {
                        // Retour en mode vidéo un peu plus tard (la photo s'enregistre)
                        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                cam.setFlatMode(SettingsDefinitions.FlatCameraMode.VIDEO_NORMAL, new CommonCallbacks.CompletionCallback() {
                                    @Override
                                    public void onResult(DJIError x) { if (x != null) cam.setMode(SettingsDefinitions.CameraMode.RECORD_VIDEO, null); }
                                });
                            }
                        }, 2500);
                        if (d != null) d.done(e);
                    }
                }));
            }
        };
        cam.setFlatMode(SettingsDefinitions.FlatCameraMode.PHOTO_SINGLE, new CommonCallbacks.CompletionCallback() {
            @Override
            public void onResult(DJIError e) {
                if (e == null) shoot.onResult(null);
                else cam.setMode(SettingsDefinitions.CameraMode.SHOOT_PHOTO, shoot);
            }
        });
    }

    /** Correction d'exposition (en IL, de -3 à +3) : la valeur la plus proche que la caméra connaît. */
    public void exposure(double ev, Done d) {
        dji.sdk.camera.Camera cam = camera();
        if (cam == null) { if (d != null) d.done("caméra indisponible"); return; }
        SettingsDefinitions.ExposureCompensation best = null;
        double bestDiff = 99;
        for (SettingsDefinitions.ExposureCompensation c : SettingsDefinitions.ExposureCompensation.values()) {
            String n = c.name();
            if (!(n.startsWith("N_") || n.startsWith("P_"))) continue;
            try {
                String[] parts = n.substring(2).split("_");
                double v = Double.parseDouble(parts[0] + "." + parts[1]) * (n.startsWith("N_") ? -1 : 1);
                if (Math.abs(v - ev) < bestDiff) { bestDiff = Math.abs(v - ev); best = c; }
            } catch (Exception ignored) { }
        }
        if (best == null) { if (d != null) d.done("valeur inconnue"); return; }
        cam.setExposureMode(SettingsDefinitions.ExposureMode.PROGRAM, null);
        cam.setExposureCompensation(best, cb(d));
    }

    /** Balance des blancs : auto, soleil, nuageux, incandescent, fluorescent. */
    public void whiteBalance(String preset, Done d) {
        dji.sdk.camera.Camera cam = camera();
        if (cam == null) { if (d != null) d.done("caméra indisponible"); return; }
        SettingsDefinitions.WhiteBalancePreset p;
        switch (preset) {
            case "soleil": p = SettingsDefinitions.WhiteBalancePreset.SUNNY; break;
            case "nuageux": p = SettingsDefinitions.WhiteBalancePreset.CLOUDY; break;
            case "incandescent": p = SettingsDefinitions.WhiteBalancePreset.INDOOR_INCANDESCENT; break;
            case "fluorescent": p = SettingsDefinitions.WhiteBalancePreset.INDOOR_FLUORESCENT; break;
            default: p = SettingsDefinitions.WhiteBalancePreset.AUTO;
        }
        cam.setWhiteBalance(new dji.common.camera.WhiteBalance(p), cb(d));
    }

    public interface MediaDone { void progress(String message); void done(int count, String error); }

    /**
     * Copie sur le téléphone les photos (et vidéos si [videos]) prises depuis [sinceMs], dans [dir].
     * Drone posé. La caméra passe en mode « téléchargement » le temps de la copie.
     */
    public void downloadMedia(final long sinceMs, final boolean videos, final java.io.File dir, final MediaDone cb) {
        final dji.sdk.camera.Camera cam = camera();
        if (cam == null || cam.getMediaManager() == null) { cb.done(0, "lecture de la carte du drone non disponible"); return; }
        final dji.sdk.media.MediaManager mm = cam.getMediaManager();
        cam.setMode(SettingsDefinitions.CameraMode.MEDIA_DOWNLOAD, new CommonCallbacks.CompletionCallback() {
            @Override
            public void onResult(DJIError e0) {
                mm.refreshFileListOfStorageLocation(SettingsDefinitions.StorageLocation.SDCARD, new CommonCallbacks.CompletionCallback() {
                    @Override
                    public void onResult(DJIError e1) {
                        if (e1 != null) {
                            back(cam);
                            String m = e1.getDescription() == null ? "" : e1.getDescription();
                            if (m.toLowerCase(java.util.Locale.ROOT).contains("sd card")) cb.done(0, "pas de carte microSD dans le drone : rien à copier. Mets-en une pour les photos, les vidéos et les MasterShots (en attendant, les photos de l'appli viennent de la vidéo en direct)");
                            else cb.done(0, "liste des fichiers impossible : " + m);
                            return;
                        }
                        java.util.List<dji.sdk.media.MediaFile> all = mm.getSDCardFileListSnapshot();
                        final java.util.ArrayList<dji.sdk.media.MediaFile> todo = new java.util.ArrayList<>();
                        if (all != null) for (dji.sdk.media.MediaFile m : all) {
                            boolean video = m.getMediaType() == dji.sdk.media.MediaFile.MediaType.MP4 || m.getMediaType() == dji.sdk.media.MediaFile.MediaType.MOV;
                            boolean photo = m.getMediaType() == dji.sdk.media.MediaFile.MediaType.JPEG;
                            if ((photo || (video && videos)) && m.getTimeCreated() >= sinceMs) todo.add(m);
                        }
                        if (todo.isEmpty()) { back(cam); cb.done(0, null); return; }
                        next(cam, todo, 0, dir, cb);
                    }
                });
            }
        });
    }

    private void next(final dji.sdk.camera.Camera cam, final java.util.List<dji.sdk.media.MediaFile> todo, final int i, final java.io.File dir, final MediaDone cb) {
        if (i >= todo.size()) { back(cam); cb.done(todo.size(), null); return; }
        final dji.sdk.media.MediaFile m = todo.get(i);
        String name = m.getFileName();
        int dot = name.lastIndexOf('.');
        final String base = dot > 0 ? name.substring(0, dot) : name;
        cb.progress("📥 " + (i + 1) + "/" + todo.size() + " : " + name);
        m.fetchFileData(dir, base, new dji.sdk.media.DownloadListener<String>() {
            @Override public void onStart() { }
            @Override public void onRateUpdate(long total, long current, long persize) { }
            @Override public void onRealtimeDataUpdate(byte[] data, long position, boolean isLastPack) { }
            @Override public void onProgress(long total, long current) { }
            @Override public void onSuccess(String path) { next(cam, todo, i + 1, dir, cb); }
            @Override public void onFailure(DJIError e) { cb.progress("échec pour " + m.getFileName() + " : " + e.getDescription()); next(cam, todo, i + 1, dir, cb); }
        });
    }

    private void back(dji.sdk.camera.Camera cam) {
        cam.setMode(SettingsDefinitions.CameraMode.RECORD_VIDEO, null);
    }

    // ---- diagnostic et réglages avant décollage ------------------------------------------------
    public interface BoolDone { void done(Boolean value, String error); }

    /** Messages d'alerte actuels du drone : {raison, solution, code}. */
    public java.util.List<String[]> djiMessages() { return djiMessages; }

    /**
     * Points vérifiés avant le décollage : {état (ok / attention / bloquant), titre, explication}.
     */
    public java.util.List<String[]> checks() {
        java.util.ArrayList<String[]> c = new java.util.ArrayList<>();
        boolean connected = isConnected();
        if (!connected) { c.add(new String[] { "bloquant", "Drone", "non connecté" }); return c; }
        // GPS
        if (satellites >= 8) c.add(new String[] { "ok", "GPS", satellites + " satellites" });
        else if (satellites >= 6) c.add(new String[] { "attention", "GPS", satellites + " satellites : faible, attends un peu à découvert" });
        else c.add(new String[] { "attention", "GPS", satellites + " satellite(s) : normal en intérieur. Le drone doit alors tenir sa position avec ses capteurs du dessous." });
        // Capteurs du dessous (caméras de positionnement + capteur de hauteur)
        String[] bs = belowState();
        if (!"inactifs".equals(bs[0])) c.add(new String[] { "ok", "Capteurs du dessous", bs[1] + (tofUsed ? String.format(java.util.Locale.FRANCE, " (hauteur mesurée %.1f m)", tofHeight) : "") });
        else c.add(new String[] { "bloquant", "Capteurs du dessous",
            bs[1] + ". Causes possibles : pièce trop sombre ; sol uni, brillant ou réfléchissant (carrelage clair, vitre, eau, moquette unie) ; " +
            "capteurs sous le drone masqués (cage, protection, autocollant, poussière : nettoie-les doucement) ; " +
            "positionnement visuel désactivé dans les réglages (bouton ci-dessous). Pose un tapis à motifs sous la zone de vol et allume la lumière." });
        if (tofError) c.add(new String[] { "bloquant", "Capteur de hauteur", "en erreur : nettoie le dessous du drone, redémarre-le" });
        // Boussole
        try {
            FlightController f = fc();
            if (f != null && f.getCompass() != null) {
                if (f.getCompass().isCalibrating()) c.add(new String[] { "attention", "Boussole", "étalonnage en cours (" + compassCal + ")" });
                else if (f.getCompass().hasError()) c.add(new String[] { "bloquant", "Boussole", "perturbée : éloigne le drone du métal (cage métallique, table, ferraille, voiture, téléphone) puis étalonne-la (bouton ci-dessous)" });
                else c.add(new String[] { "ok", "Boussole", "normale" });
            }
        } catch (Throwable ignored) { }
        if (imuPreheating) c.add(new String[] { "attention", "Centrale inertielle", "préchauffage : attends 1 à 2 minutes" });
        Integer b = battery;
        if (b != null) c.add(new String[] { b < 20 ? "bloquant" : b < 35 ? "attention" : "ok", "Batterie", b + " %" });
        c.add(new String[] { flightMode.contains("ATTI") ? "attention" : "ok", "Mode de vol",
            (flightMode.isEmpty() ? "?" : flightMode) + (flightMode.contains("ATTI") ? " : le drone ne tient pas sa position tout seul (il dérive)" : "") });
        return c;
    }

    /** Étalonnage de la boussole : le drone se tourne à la main (à plat puis nez vers le bas). */
    public void compassCalibration(boolean start, Done d) {
        FlightController f = fc();
        if (noFc(f, d)) return;
        if (f.getCompass() == null) { if (d != null) d.done("boussole indisponible"); return; }
        if (start) f.getCompass().startCalibration(cb(d)); else f.getCompass().stopCalibration(cb(d));
    }

    public String compassCalibrationState() { return compassCal; }

    /** Positionnement visuel (capteurs du dessous). */
    public void visionPositioning(boolean on, Done d) {
        FlightController f = fc();
        if (noFc(f, d)) return;
        FlightAssistantHolder.set(f, on, cb(d), d);
    }

    public void getVisionPositioning(final BoolDone d) {
        FlightController f = fc();
        if (f == null || f.getFlightAssistant() == null) { d.done(null, "réglage indisponible sur ce drone"); return; }
        f.getFlightAssistant().getVisionAssistedPositioningEnabled(new CommonCallbacks.CompletionCallbackWith<Boolean>() {
            @Override public void onSuccess(Boolean v) { d.done(v, null); }
            @Override public void onFailure(DJIError e) { d.done(null, e.getDescription()); }
        });
    }

    /** Décollage sans GPS : interdit (verrou) ou autorisé. */
    public void getTakeoffWithoutGpsAllowed(final BoolDone d) {
        FlightController f = fc();
        if (f == null) { d.done(null, "drone non connecté"); return; }
        try {
            f.isLockTakeoffWithoutGPS(new CommonCallbacks.CompletionCallbackWith<Boolean>() {
                @Override public void onSuccess(Boolean locked) { d.done(locked == null ? null : !locked, null); }
                @Override public void onFailure(DJIError e) { d.done(null, e.getDescription()); }
            });
        } catch (Throwable t) { d.done(null, "réglage indisponible sur ce drone"); }
    }

    @SuppressWarnings("unchecked")
    public void allowTakeoffWithoutGps(boolean allow, Done d) {
        FlightController f = fc();
        if (noFc(f, d)) return;
        try { f.lockTakeoffWithoutGPS(!allow, cb(d)); } catch (Throwable t) { if (d != null) d.done("réglage indisponible sur ce drone"); }
    }

    /** Étalonnage de la centrale inertielle (drone posé à plat, ne pas le toucher). */
    public void imuCalibration(Done d) {
        FlightController f = fc();
        if (noFc(f, d)) return;
        f.startIMUCalibration(cb(d));
    }

    /** Redémarre le contrôleur de vol (le drone doit être posé). */
    public void rebootFlightController(Done d) {
        FlightController f = fc();
        if (noFc(f, d)) return;
        f.reboot(cb(d));
    }

    private static class FlightAssistantHolder {
        static void set(FlightController f, boolean on, CommonCallbacks.CompletionCallback cb, Done d) {
            if (f.getFlightAssistant() == null) { if (d != null) d.done("réglage indisponible sur ce drone"); return; }
            f.getFlightAssistant().setVisionAssistedPositioningEnabled(on, cb);
        }
    }

    private void status(String s) {
        Listener l = listener;
        if (l != null) l.onStatus(s);
    }

    /** Enregistre l'application auprès de DJI (Internet nécessaire la première fois), puis cherche le drone. */
    public void register(Context ctx, Listener l) {
        listener = l;
        if (!DjiBoot.INSTANCE.getReady()) {
            status("Kit DJI non chargé sur ce téléphone.");
            return;
        }
        if (registered) {
            DJISDKManager.getInstance().startConnectionToProduct();
            return;
        }
        DJISDKManager.getInstance().registerApp(ctx.getApplicationContext(), new DJISDKManager.SDKManagerCallback() {
            @Override
            public void onRegister(DJIError error) {
                if (error == DJISDKError.REGISTRATION_SUCCESS) {
                    registered = true;
                    status("Application reconnue par DJI. Recherche du drone…");
                    DJISDKManager.getInstance().startConnectionToProduct();
                } else {
                    status("Enregistrement DJI refusé : " + (error == null ? "?" : error.getDescription())
                        + ". Vérifie la clé DJI de l'application et la connexion Internet.");
                }
            }

            @Override
            public void onProductDisconnect() {
                Listener li = listener;
                if (li != null) li.onProductChanged(false);
            }

            @Override
            public void onProductConnect(BaseProduct product) {
                attachCallbacks();
                Listener li = listener;
                if (li != null) li.onProductChanged(true);
            }

            @Override
            public void onProductChanged(BaseProduct product) {
                attachCallbacks();
            }

            @Override
            public void onComponentChange(BaseProduct.ComponentKey key, BaseComponent oldComponent, BaseComponent newComponent) {
                attachCallbacks();
            }

            @Override
            public void onInitProcess(DJISDKInitEvent event, int totalProcess) {}

            @Override
            public void onDatabaseDownloadProgress(long current, long total) {}
        });
    }

    private Aircraft aircraft() {
        if (!registered) return null;
        BaseProduct p = DJISDKManager.getInstance().getProduct();
        return (p instanceof Aircraft) ? (Aircraft) p : null;
    }

    private FlightController fc() {
        Aircraft a = aircraft();
        return a == null ? null : a.getFlightController();
    }

    public boolean isConnected() {
        Aircraft a = aircraft();
        return a != null && a.isConnected() && a.getFlightController() != null;
    }

    public String model() {
        Aircraft a = aircraft();
        if (a == null || a.getModel() == null) return "drone DJI";
        return a.getModel().getDisplayName();
    }

    /** (Ré)abonne l'application au flux vidéo principal du drone. */
    public synchronized void ensureVideoListener() {
        try {
            VideoFeeder vf = VideoFeeder.getInstance();
            if (vf == null) return;
            VideoFeeder.VideoFeed feed = vf.getPrimaryVideoFeed();
            if (feed == null) return;
            if (listenedFeed != null && listenedFeed != feed) listenedFeed.removeVideoDataListener(videoListener);
            feed.removeVideoDataListener(videoListener);
            feed.addVideoDataListener(videoListener);
            listenedFeed = feed;
        } catch (Throwable t) {
            Log.e(TAG, "abonnement vidéo", t);
        }
    }

    /** Débit du flux vidéo reçu et état du décodeur, pour le diagnostic. */
    public String videoStats() {
        long now = System.currentTimeMillis();
        if (now - checkAt > 1500) {
            if (checkAt > 0) packetRate = (packets - packetsAtCheck) * 1000.0 / (now - checkAt);
            packetsAtCheck = packets;
            checkAt = now;
        }
        return String.format(java.util.Locale.FRANCE, "vidéo DJI : %.0f paquets/s, %d reçus, décodeur %s",
            packetRate, packets, codec != null ? "prêt" : "absent");
    }

    private void attachCallbacks() {
        Aircraft a = aircraft();
        if (a == null) return;
        ensureVideoListener();
        FlightController f = a.getFlightController();
        if (f != null) {
            f.setStateCallback(new FlightControllerState.Callback() {
                @Override
                public void onUpdate(FlightControllerState s) {
                    flying = s.isFlying();
                    landingConfirmationNeeded = s.isLandingConfirmationNeeded();
                    if (s.getAircraftLocation() != null) altitude = s.getAircraftLocation().getAltitude();
                    flightMode = s.getFlightModeString() == null ? "" : s.getFlightModeString();
                    imuPreheating = s.isIMUPreheating();
                    tofUsed = s.isUltrasonicBeingUsed();
                    tofError = s.doesUltrasonicHaveError();
                    tofHeight = s.getUltrasonicHeightInMeters();
                    gps = s.getGPSSignalLevel() == null ? "?" : s.getGPSSignalLevel().name();
                    satellites = s.getSatelliteCount();
                    vps = s.isVisionPositioningSensorBeingUsed();
                    motorsOn = s.areMotorsOn();
                    lowBattery = s.isLowerThanBatteryWarningThreshold();
                    if (s.getAttitude() != null) {
                        heading = s.getAttitude().yaw;
                        attPitch = s.getAttitude().pitch;
                        attRoll = s.getAttitude().roll;
                    }
                    vx = s.getVelocityX(); vy = s.getVelocityY(); vz = s.getVelocityZ();
                    flightTime = s.getFlightTimeInSeconds();
                    homeSet = s.isHomeLocationSet();
                    if (s.getAircraftLocation() != null) { lat = s.getAircraftLocation().getLatitude(); lon = s.getAircraftLocation().getLongitude(); }
                    if (s.getHomeLocation() != null) { homeLat = s.getHomeLocation().getLatitude(); homeLon = s.getHomeLocation().getLongitude(); }
                }
            });
        }
        if (a.getRemoteController() != null) {
            a.getRemoteController().setHardwareStateCallback(new dji.common.remotecontroller.HardwareState.HardwareStateCallback() {
                @Override
                public void onUpdate(dji.common.remotecontroller.HardwareState hs) {
                    int m = 0;
                    if (hs.getLeftStick() != null) m = Math.max(m, Math.max(Math.abs(hs.getLeftStick().getHorizontalPosition()), Math.abs(hs.getLeftStick().getVerticalPosition())));
                    if (hs.getRightStick() != null) m = Math.max(m, Math.max(Math.abs(hs.getRightStick().getHorizontalPosition()), Math.abs(hs.getRightStick().getVerticalPosition())));
                    // Manches de -660 à 660 : au-delà de ~20 %, c'est un geste volontaire du pilote.
                    if (m > 130) {
                        Listener li = listener;
                        if (li != null) li.onPilotSticks();
                    }
                }
            });
        }
        // Messages d'alerte du drone (ceux que DJI Fly affiche) : raison et solution
        try {
            a.setDiagnosticsInformationCallback(new dji.sdk.base.DJIDiagnostics.DiagnosticsInformationCallback() {
                @Override
                public void onUpdate(java.util.List<dji.sdk.base.DJIDiagnostics> list) {
                    java.util.ArrayList<String[]> out = new java.util.ArrayList<>();
                    if (list != null) for (dji.sdk.base.DJIDiagnostics d : list) {
                        out.add(new String[] { d.getReason() == null ? "" : d.getReason(), d.getSolution() == null ? "" : d.getSolution(), String.valueOf(d.getCode()) });
                    }
                    djiMessages = out;
                }
            });
        } catch (Throwable t) { Log.w(TAG, "diagnostics DJI", t); }
        if (f != null && f.getCompass() != null) {
            f.getCompass().setCalibrationStateCallback(new dji.common.flightcontroller.CompassCalibrationState.Callback() {
                @Override
                public void onUpdate(dji.common.flightcontroller.CompassCalibrationState st) {
                    compassCal = st == null ? "UNKNOWN" : st.name();
                }
            });
        }
        if (a.getAirLink() != null) {
            a.getAirLink().setUplinkSignalQualityCallback(new dji.common.airlink.SignalQualityCallback() {
                @Override
                public void onUpdate(int q) { upQuality = q; qualityAt = System.currentTimeMillis(); }
            });
            a.getAirLink().setDownlinkSignalQualityCallback(new dji.common.airlink.SignalQualityCallback() {
                @Override
                public void onUpdate(int q) { downQuality = q; qualityAt = System.currentTimeMillis(); }
            });
        }
        if (a.getGimbal() != null) {
            a.getGimbal().setStateCallback(new dji.common.gimbal.GimbalState.Callback() {
                @Override
                public void onUpdate(dji.common.gimbal.GimbalState g) {
                    if (g.getAttitudeInDegrees() != null) gimbalPitchDeg = g.getAttitudeInDegrees().getPitch();
                }
            });
        }
        if (a.getBattery() != null) {
            a.getBattery().setStateCallback(new BatteryState.Callback() {
                @Override
                public void onUpdate(BatteryState s) {
                    battery = s.getChargeRemainingInPercent();
                }
            });
        }
    }

    private static CommonCallbacks.CompletionCallback cb(final Done d) {
        return new CommonCallbacks.CompletionCallback() {
            @Override
            public void onResult(DJIError error) {
                if (d != null) d.done(error == null ? null : error.getDescription() + " (code " + error.getErrorCode() + ")");
            }
        };
    }

    private static boolean noFc(FlightController f, Done d) {
        if (f != null) return false;
        if (d != null) d.done("drone non connecté");
        return true;
    }

    // ---- vol ------------------------------------------------------------------------------------
    public void takeoff(Done d) {
        FlightController f = fc();
        if (noFc(f, d)) return;
        f.startTakeoff(cb(d));
    }

    public void land(Done d) {
        FlightController f = fc();
        if (noFc(f, d)) return;
        f.startLanding(cb(d));
    }

    public void confirmLanding(Done d) {
        FlightController f = fc();
        if (noFc(f, d)) return;
        f.confirmLanding(cb(d));
    }

    public void motorsOff(Done d) {
        FlightController f = fc();
        if (noFc(f, d)) return;
        f.turnOffMotors(cb(d));
    }

    /** Pilotage par l'application : vitesses dans le repère du drone. */
    public void enableVirtualStick(final Done d) {
        final FlightController f = fc();
        if (noFc(f, d)) return;
        f.setRollPitchControlMode(RollPitchControlMode.VELOCITY);
        f.setYawControlMode(YawControlMode.ANGULAR_VELOCITY);
        f.setVerticalControlMode(VerticalControlMode.VELOCITY);
        f.setRollPitchCoordinateSystem(FlightCoordinateSystem.BODY);
        f.setVirtualStickModeEnabled(true, cb(d));
    }

    public void disableVirtualStick(Done d) {
        FlightController f = fc();
        if (noFc(f, d)) return;
        f.setVirtualStickModeEnabled(false, cb(d));
    }

    /**
     * Commande de vitesse, à envoyer 10 fois par seconde.
     * right, forward, vertical en m/s (vertical > 0 = monter), yawRate en °/s (> 0 = à droite).
     */
    public void sendSticks(float right, float forward, float yawRate, float vertical) {
        FlightController f = fc();
        if (f == null) return;
        // Les noms sont inversés dans le kit DJI : le 1er paramètre (« pitch ») reçoit la vitesse latérale,
        // le 2e (« roll ») la vitesse vers l'avant (voir l'exemple officiel).
        f.sendVirtualStickFlightControlData(new FlightControlData(right, forward, yawRate, vertical), null);
    }

    public void setLimits(int maxHeightM, int radiusM) {
        FlightController f = fc();
        if (f == null) return;
        // DJI impose au moins 20 m de plafond ; l'application applique son propre plafond, plus bas.
        f.setMaxFlightHeight(Math.max(20, maxHeightM), null);
        f.setMaxFlightRadiusLimitationEnabled(true, null);
        f.setMaxFlightRadius(Math.max(15, radiusM), null);
    }

    public void gimbalPitch(float degrees, Done d) {
        Aircraft a = aircraft();
        Gimbal g = a == null ? null : a.getGimbal();
        if (g == null) {
            if (d != null) d.done("nacelle indisponible");
            return;
        }
        Rotation r = new Rotation.Builder().mode(RotationMode.ABSOLUTE_ANGLE).pitch(degrees).time(1.0).build();
        g.rotate(r, cb(d));
    }

    /**
     * Remet la caméra en exposition automatique (des réglages manuels laissés dans DJI Fly
     * peuvent rendre l'image très sombre). brighter : correction +1 pour les pièces peu éclairées.
     */
    public void autoExposure(boolean brighter, final Done d) {
        BaseProduct p = DJISDKManager.getInstance().getProduct();
        final dji.sdk.camera.Camera cam = p == null ? null : p.getCamera();
        if (cam == null) {
            if (d != null) d.done("caméra indisponible");
            return;
        }
        final SettingsDefinitions.ExposureCompensation ev =
            brighter ? SettingsDefinitions.ExposureCompensation.P_1_0 : SettingsDefinitions.ExposureCompensation.N_0_0;
        cam.setAELock(false, null);
        cam.setExposureMode(SettingsDefinitions.ExposureMode.PROGRAM, new CommonCallbacks.CompletionCallback() {
            @Override
            public void onResult(DJIError e1) {
                cam.setExposureCompensation(ev, cb(d == null ? null : new Done() {
                    @Override
                    public void done(String e2) {
                        d.done(e1 != null ? "mode auto : " + e1.getDescription() : e2);
                    }
                }));
            }
        });
    }

    // ---- vidéo ----------------------------------------------------------------------------------
    /** Décode la vidéo dans la texture affichée à l'écran. */
    public synchronized void startVideo(Context ctx, SurfaceTexture st, int w, int h) {
        stopVideo();
        if (!registered || st == null) return;
        try {
            codec = new DJICodecManager(ctx.getApplicationContext(), st, w, h);
            ensureVideoListener();
        } catch (Throwable t) {
            Log.e(TAG, "vidéo", t);
            status("Vidéo DJI indisponible : " + t.getMessage());
        }
    }

    /** Arrête le décodage (l'abonnement au flux reste, pour le diagnostic). */
    public synchronized void stopVideo() {
        DJICodecManager c = codec;
        codec = null;
        if (c != null) {
            try { c.cleanSurface(); } catch (Throwable ignored) { }
            try { c.destroyCodec(); } catch (Throwable ignored) { }
        }
    }
}
