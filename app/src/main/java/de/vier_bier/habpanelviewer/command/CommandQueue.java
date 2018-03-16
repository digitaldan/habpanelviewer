package de.vier_bier.habpanelviewer.command;

import android.app.Activity;
import android.content.SharedPreferences;
import android.util.Log;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;

import de.vier_bier.habpanelviewer.command.log.CommandInfo;
import de.vier_bier.habpanelviewer.command.log.CommandLog;
import de.vier_bier.habpanelviewer.command.log.CommandLogClient;
import de.vier_bier.habpanelviewer.openhab.IStateUpdateListener;
import de.vier_bier.habpanelviewer.openhab.ServerConnection;

/**
 * Queue for commands sent from openHAB.
 */
public class CommandQueue implements IStateUpdateListener {
    private final Activity mCtx;
    private final ServerConnection mServerConnection;

    private final ArrayList<ICommandHandler> mHandlers = new ArrayList<>();
    private final CommandLog mCmdLog = new CommandLog();

    public CommandQueue(Activity ctx, ServerConnection serverConnection) {
        EventBus.getDefault().register(this);

        mCtx = ctx;
        mServerConnection = serverConnection;
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onMessageEvent(CommandLogClient client) {
        client.setCommandLog(mCmdLog);
    }

    public void addHandler(ICommandHandler h) {
        synchronized (mHandlers) {
            if (!mHandlers.contains(h)) {
                mHandlers.add(h);
            }
        }
    }

    @Override
    public void itemUpdated(String name, String value) {
        if (value != null && !value.isEmpty()) {
            synchronized (mHandlers) {
                for (ICommandHandler mHandler : mHandlers) {
                    try {
                        if (mHandler.handleCommand(value)) {
                            addToCmdLog(new CommandInfo(value, true));
                            return;
                        }
                    } catch (Throwable t) {
                        Log.e("Habpanelview", "unhandled exception", t);
                        addToCmdLog(new CommandInfo(value, true, t));
                        return;
                    }
                }
            }

            Log.w("Habpanelview", "received unhandled command: " + value);
            addToCmdLog(new CommandInfo(value, false));
        }
    }

    private void addToCmdLog(final CommandInfo cmd) {
        mCtx.runOnUiThread(() -> mCmdLog.add(cmd));
    }

    public void updateFromPreferences(final SharedPreferences prefs) {
        String mCmdItemName = prefs.getString("pref_command_item", "");

        mCtx.runOnUiThread(() -> mCmdLog.setSize(prefs.getInt("pref_command_log_size", 100)));

        mServerConnection.subscribeCommandItems(this, mCmdItemName);
    }
}
