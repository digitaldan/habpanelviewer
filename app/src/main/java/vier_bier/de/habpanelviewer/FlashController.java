package vier_bier.de.habpanelviewer;

import android.content.SharedPreferences;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Created by volla on 07.09.17.
 */
public class FlashController implements StateListener {
    private FlashControlThread controller;
    private CameraManager camManager;
    private String torchId;

    private boolean enabled;
    private String flashItemName;
    private String flashItemState;

    private Pattern flashOnPattern;
    private Pattern flashPulsatingPattern;

    public FlashController(CameraManager cameraManager) throws CameraAccessException {
        camManager = cameraManager;

        for (String camId : camManager.getCameraIdList()) {
            CameraCharacteristics characteristics = camManager.getCameraCharacteristics(camId);
            Boolean hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);

            if (facing == CameraCharacteristics.LENS_FACING_BACK && hasFlash) {
                torchId = camId;
                break;
            }
        }

        if (torchId == null) {
            throw new CameraAccessException(CameraAccessException.CAMERA_ERROR, "Could not find back facing camera with flash!");
        }
    }

    public String getItemName() {
        return flashItemName;
    }

    public String getItemState() {
        return flashItemState;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void terminate() {
        if (controller != null) {
            controller.terminate();
            controller = null;
        }
    }

    private FlashControlThread createController() {
        if (controller == null) {
            controller = new FlashControlThread();
            controller.start();
        }

        return controller;
    }

    @Override
    public void updateState(String name, String state) {
        if (name.equals(flashItemName)) {
            if (flashItemState != null && flashItemState.equals(state)) {
                Log.i("Habpanelview", "unchanged flash item state=" + state);
                return;
            }

            Log.i("Habpanelview", "flash item state=" + state + ", old state=" + flashItemState);
            flashItemState = state;

            if (flashOnPattern != null && flashOnPattern.matcher(state).matches()) {
                createController().enableFlash();
            } else if (flashPulsatingPattern != null && flashPulsatingPattern.matcher(state).matches()) {
                createController().pulseFlash();
            } else {
                if (controller != null) {
                    controller.disableFlash();
                }
            }
        }
    }

    public void updateFromPreferences(SharedPreferences prefs) {
        flashPulsatingPattern = null;
        flashOnPattern = null;
        flashItemName = prefs.getString("pref_flash_item", "");
        enabled = prefs.getBoolean("pref_flash_enabled", false);

        String pulsatingRegexpStr = prefs.getString("pref_flash_pulse_regex", "");
        if (!pulsatingRegexpStr.isEmpty()) {
            try {
                flashPulsatingPattern = Pattern.compile(pulsatingRegexpStr);
            } catch (PatternSyntaxException e) {
                // is handled in the preferences
            }
        }

        String steadyRegexpStr = prefs.getString("pref_flash_steady_regex", "");
        if (!steadyRegexpStr.isEmpty()) {
            try {
                flashOnPattern = Pattern.compile(steadyRegexpStr);
            } catch (PatternSyntaxException e) {
                // is handled in the preferences
            }
        }
    }

    private class FlashControlThread extends Thread {
        private final AtomicBoolean fRunning = new AtomicBoolean(true);
        private AtomicBoolean fPulsing = new AtomicBoolean(false);
        private AtomicBoolean fOn = new AtomicBoolean(false);

        private boolean fFlashOn = false;

        public FlashControlThread() {
            super("FlashControlThread");
            setDaemon(true);
        }

        private void terminate() {
            fRunning.set(false);
            try {
                join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void run() {
            Log.d("Habpanelview", "FlashControlThread started");

            while (fRunning.get()) {
                synchronized (fRunning) {
                    setFlash(fOn.get() || fPulsing.get() && !fFlashOn);

                    try {
                        fRunning.wait(1000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }

            setFlash(false);
            Log.d("Habpanelview", "FlashControlThread finished");
        }

        public void pulseFlash() {
            Log.d("Habpanelview", "pulseFlash");

            synchronized (fRunning) {
                fPulsing.set(true);
                fOn.set(false);

                fRunning.notifyAll();
            }
        }

        public void disableFlash() {
            Log.d("Habpanelview", "disableFlash");

            synchronized (fRunning) {
                fPulsing.set(false);
                fOn.set(false);

                fRunning.notifyAll();
            }
        }

        public void enableFlash() {
            Log.d("Habpanelview", "disableFlash");

            synchronized (fRunning) {
                fPulsing.set(false);
                fOn.set(true);

                fRunning.notifyAll();
            }
        }

        private void setFlash(boolean flashing) {
            if (flashing != fFlashOn) {
                fFlashOn = flashing;

                try {
                    if (torchId != null) {
                        Log.d("Habpanelview", "Set torchmode " + flashing);
                        camManager.setTorchMode(torchId, flashing);
                    }
                } catch (CameraAccessException e) {
                    Log.e("Habpanelview", "Failed to toggle flash!", e);
                }
            }
        }
    }
}
