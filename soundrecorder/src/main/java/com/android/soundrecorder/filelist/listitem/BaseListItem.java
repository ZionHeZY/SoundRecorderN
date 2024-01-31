package com.android.soundrecorder.filelist.listitem;

public abstract class BaseListItem {
    public static final int SUPPORT_DELETE = 1 << 0;
    public static final int SUPPORT_SHARE = 1 << 1;
    public static final int SUPPORT_EDIT = 1 << 2;
    public static final int SUPPORT_NONE = 0;
    public static final int SUPPORT_ALL = 0xffffffff;

    protected long mId;
    protected String mTitle;
    protected boolean mChecked;
    protected String mPath;
    protected boolean mSelectable = true;

    protected int mItemType;
    public static final int TYPE_NONE = 0;
    public static final int TYPE_FOLDER = 1;
    public static final int TYPE_MEDIA_ITEM = 2;

    protected int mSupportedOperation;

    public long getId() {
        return mId;
    }

    public void setId(long id) {
        mId = id;
    }

    public String getTitle() {
        return mTitle;
    }

    public void setTitle(String title) {
        mTitle = title;
    }

    public String getPath() {
        return mPath;
    }

    public void setPath(String path) {
        mPath = path;
    }

    public boolean isChecked() {
        return mSelectable && mChecked;
    }

    public void setChecked(boolean checked) {
        mChecked = checked;
    }

    public int getItemType() {
        return mItemType;
    }

    protected void setItemType(int itemType) {
        mItemType = itemType;
    }

    public boolean isSelectable() {
        return mSelectable;
    }

    protected void setSelectable(boolean selectable) {
        mSelectable = selectable;
    }

    public int getSupportedOperation() {
        return mSupportedOperation;
    }

    protected void setSupportedOperation(int operation) {
        mSupportedOperation = operation;
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof BaseListItem) {
            return getId() == ((BaseListItem) o).getId();
        } else {
            return super.equals(o);
        }
    }

    public void copyFrom(BaseListItem item) {
        setTitle(item.getTitle());
        setPath(item.getPath());
        setItemType(item.getItemType());
        setSelectable(item.isSelectable());
        setSupportedOperation(item.getSupportedOperation());
        setChecked(item.isChecked());
    }
}
