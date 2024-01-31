package util;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.support.v4.content.PermissionChecker;

import java.util.ArrayList;

public class PermissionUtils {
    public enum PermissionType {
        RECORD, PLAY
    }

    private static String[] RECORD_PERMISSIONS = {
            Manifest.permission.READ_PHONE_STATE, Manifest.permission.RECORD_AUDIO,
            Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE
    };

    private static String[] PLAY_PERMISSIONS = {
            Manifest.permission.READ_EXTERNAL_STORAGE
    };

    public static String[] getOperationPermissions(PermissionType type) {
        switch (type) {
            case RECORD:
                return RECORD_PERMISSIONS;
            case PLAY:
                return PLAY_PERMISSIONS;
            default:
                return null;
        }
    }

    public static boolean checkPermissions(final Activity activity, PermissionType type,
                                           int operationHandle) {
        String[] permissions = getOperationPermissions(type);
        return checkPermissions(activity, permissions, operationHandle);
    }

    public static boolean checkPermissions(final Activity activity, String[] permissions,
                                           int operationHandle) {
        if (permissions == null)
            return true;
        boolean isPermissionGranted = true;
        ArrayList<String> permissionList = new ArrayList<String>();

        int targetSdkVersion = 0;
        try {
            final PackageInfo info = activity.getPackageManager().getPackageInfo(
                    activity.getPackageName(), 0);
            targetSdkVersion = info.applicationInfo.targetSdkVersion;
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (targetSdkVersion >= Build.VERSION_CODES.M) {
                for (String permission : permissions) {
                    if (PackageManager.PERMISSION_GRANTED != activity.checkSelfPermission(permission)) {
                        permissionList.add(permission);
                        isPermissionGranted = false;
                    }
                }
                if (!isPermissionGranted) {
                    String[] permissionArray = new String[permissionList.size()];
                    permissionList.toArray(permissionArray);
                    activity.requestPermissions(permissionArray, operationHandle);
                }
            } else {
                for (String permission : permissions) {
                    isPermissionGranted = PermissionChecker.checkSelfPermission(activity, permission)
                            == PermissionChecker.PERMISSION_GRANTED;
                }
                if (!isPermissionGranted) {
                    String[] permissionArray = new String[permissionList.size()];
                    permissionList.toArray(permissionArray);
                    activity.requestPermissions(permissionArray, operationHandle);
                }
            }
        }
        return isPermissionGranted;
    }

    public static boolean checkPermissionResult(String[] permissions, int[] grantResults) {
        if (permissions == null || grantResults == null || permissions.length == 0
                || grantResults.length == 0) {
            return false;
        }

        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }
}
