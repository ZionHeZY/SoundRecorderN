package com.android.soundrecorder.filelist.listitem;

public class FolderItem extends BaseListItem {
    public FolderItem(long id, String path) {
        setItemType(BaseListItem.TYPE_FOLDER);
        setId(id);
        setPath(path);
        setSelectable(true);
        setSupportedOperation(SUPPORT_SHARE | SUPPORT_DELETE);
    }
}
