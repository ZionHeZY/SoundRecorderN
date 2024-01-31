package com.android.soundrecorder.filelist;

import android.app.Activity;
import android.app.Fragment;
import android.os.Bundle;
import android.view.MenuItem;

import com.android.soundrecorder.R;
import com.android.soundrecorder.filelist.player.Player;
import com.android.soundrecorder.filelist.player.PlayerPanel;

import util.PermissionUtils;


public class FileListActivity extends Activity {
    private Player mPlayer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.file_list_activity);
        PlayerPanel playerPanel = (PlayerPanel) findViewById(R.id.player_panel);
        mPlayer = new Player(getApplicationContext(), playerPanel);

        FileListFragment fragment = new FileListFragment();
        getFragmentManager().beginTransaction()
                .replace(android.R.id.content, fragment, FileListFragment.FRAGMENT_TAG)
                .commit();
    }

    /**
     * This request result is from FileListFragment.
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        if (PermissionUtils.checkPermissionResult(permissions, grantResults)) {
            reloadFragmentAdapter();
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                onBackPressed();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    public void reloadFragmentAdapter() {
        Fragment fragment = getFragmentManager().findFragmentByTag(FileListFragment.FRAGMENT_TAG);
        if (fragment != null && fragment instanceof FileListFragment) {
            ((FileListFragment) fragment).reloadAdapter();
        }
    }

    public Player getPlayer() {
        return mPlayer;
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (getPlayer() != null) {
            getPlayer().pausePlayer();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (getPlayer() != null) {
            getPlayer().stopPlayer();
        }
    }
}
