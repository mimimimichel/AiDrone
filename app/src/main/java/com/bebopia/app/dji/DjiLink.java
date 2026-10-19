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
    private volatile String gps = "?";
    private volatile int satellites = 0;
    private volatile boolean vps = false;
    private volatile boolean motorsOn = false;
    private volatile boolean lowBattery = false;
    private volatile double heading = 0;
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
    /** Cap du drone (boussole), en degrés de -180 à 180. */
    public double heading() { return heading; }

    /** Nacelle vers un angle, sans attendre la réponse (pour suivre la tête en continu). */
    public void gimbalPitchFast(float degrees) {
        Aircraft a = aircraft();
        Gimbal g = a == null ? null : a.getGimbal();
        if (g == null) return;
        g.rotate(new Rotation.Builder().mode(RotationMode.ABSOLUTE_ANGLE).pitch(degrees).time(0.2).build(), null);
    }

    public boolean hasRecentFrame() { return System.currentTimeMillis() - lastFrameAt < 1500; }

    /** État du drone en clair, pour comprendre un refus (décollage…). */
    public String diagnostics() {
        StringBuilder b = new StringBuilder();
        b.append("mode de vol ").append(flightMode.isEmpty() ? "?" : flightMode);
        b.append(", GPS ").append(gps).append(" (").append(satellites).append(" satellites)");
        b.append(", capteurs du dessous ").append(vps ? "actifs" : "inactifs");
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
        return b.toString();
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
                    gps = s.getGPSSignalLevel() == null ? "?" : s.getGPSSignalLevel().name();
                    satellites = s.getSatelliteCount();
                    vps = s.isVisionPositioningSensorBeingUsed();
                    motorsOn = s.areMotorsOn();
                    lowBattery = s.isLowerThanBatteryWarningThreshold();
                    if (s.getAttitude() != null) heading = s.getAttitude().yaw;
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
