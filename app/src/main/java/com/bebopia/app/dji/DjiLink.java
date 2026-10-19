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

    private static volatile boolean helperInstalled = false;

    /** À appeler dans Application.attachBaseContext : charge les classes du kit DJI. */
    public static void install(Context app) {
        try {
            com.cySdkyc.clx.Helper.install((android.app.Application) app);
            helperInstalled = true;
        } catch (Throwable t) {
            Log.e(TAG, "kit DJI non chargé", t);
        }
    }

    public static boolean available() {
        return helperInstalled;
    }

    private volatile boolean registered = false;
    private volatile Listener listener;
    private volatile Integer battery = null;
    private volatile float altitude = 0f;
    private volatile boolean flying = false;
    private volatile boolean landingConfirmationNeeded = false;
    private volatile String flightMode = "";
    private volatile long lastFrameAt = 0L;
    private DJICodecManager codec;
    private VideoFeeder.VideoDataListener videoListener;

    public boolean isRegistered() { return registered; }
    public Integer battery() { return battery; }
    public float altitude() { return altitude; }
    public boolean flying() { return flying; }
    public boolean landingConfirmationNeeded() { return landingConfirmationNeeded; }
    public String flightMode() { return flightMode; }
    public boolean hasRecentFrame() { return System.currentTimeMillis() - lastFrameAt < 1500; }

    private void status(String s) {
        Listener l = listener;
        if (l != null) l.onStatus(s);
    }

    /** Enregistre l'application auprès de DJI (Internet nécessaire la première fois), puis cherche le drone. */
    public void register(Context ctx, Listener l) {
        listener = l;
        if (!helperInstalled) {
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

    private void attachCallbacks() {
        Aircraft a = aircraft();
        if (a == null) return;
        FlightController f = a.getFlightController();
        if (f != null) {
            f.setStateCallback(new FlightControllerState.Callback() {
                @Override
                public void onUpdate(FlightControllerState s) {
                    flying = s.isFlying();
                    landingConfirmationNeeded = s.isLandingConfirmationNeeded();
                    if (s.getAircraftLocation() != null) altitude = s.getAircraftLocation().getAltitude();
                    flightMode = s.getFlightModeString() == null ? "" : s.getFlightModeString();
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
                if (d != null) d.done(error == null ? null : error.getDescription());
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

    // ---- vidéo ----------------------------------------------------------------------------------
    public synchronized void startVideo(Context ctx, SurfaceTexture st, int w, int h) {
        stopVideo();
        if (!registered || st == null) return;
        try {
            codec = new DJICodecManager(ctx.getApplicationContext(), st, w, h);
            final DJICodecManager c = codec;
            videoListener = new VideoFeeder.VideoDataListener() {
                @Override
                public void onReceive(byte[] buffer, int size) {
                    lastFrameAt = System.currentTimeMillis();
                    c.sendDataToDecoder(buffer, size);
                }
            };
            VideoFeeder.getInstance().getPrimaryVideoFeed().addVideoDataListener(videoListener);
        } catch (Throwable t) {
            Log.e(TAG, "vidéo", t);
            status("Vidéo DJI indisponible : " + t.getMessage());
        }
    }

    public synchronized void stopVideo() {
        try {
            if (videoListener != null && VideoFeeder.getInstance() != null && VideoFeeder.getInstance().getPrimaryVideoFeed() != null) {
                VideoFeeder.getInstance().getPrimaryVideoFeed().removeVideoDataListener(videoListener);
            }
        } catch (Throwable ignored) { }
        videoListener = null;
        if (codec != null) {
            try { codec.cleanSurface(); } catch (Throwable ignored) { }
            try { codec.destroyCodec(); } catch (Throwable ignored) { }
        }
        codec = null;
    }
}
