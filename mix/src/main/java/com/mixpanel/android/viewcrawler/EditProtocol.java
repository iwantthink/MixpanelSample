package com.mixpanel.android.viewcrawler;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.accessibility.AccessibilityEvent;
import android.widget.RelativeLayout;

import com.mixpanel.android.mpmetrics.ResourceIds;
import com.mixpanel.android.util.ImageStore;
import com.mixpanel.android.util.JSONUtils;
import com.mixpanel.android.util.MPLog;
import com.mixpanel.android.util.MPPair;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 由ViewCrawlerHandler 创建
 */
/* package */ class EditProtocol {

    public static class BadInstructionsException extends Exception {
        private static final long serialVersionUID = -4062004792184145311L;

        public BadInstructionsException(String message) {
            super(message);
        }

        public BadInstructionsException(String message, Throwable e) {
            super(message, e);
        }
    }

    public static class InapplicableInstructionsException extends BadInstructionsException {
        private static final long serialVersionUID = 3977056710817909104L;

        public InapplicableInstructionsException(String message) {
            super(message);
        }
    }

    public static class CantGetEditAssetsException extends Exception {
        public CantGetEditAssetsException(String message) {
            super(message);
        }

        public CantGetEditAssetsException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class Edit {
        private Edit(ViewVisitor aVisitor, List<String> someUrls) {
            visitor = aVisitor;
            imageUrls = someUrls;
        }

        public final ViewVisitor visitor;
        public final List<String> imageUrls;
    }

    public EditProtocol(Context context, ResourceIds resourceIds, ImageStore imageStore, ViewVisitor.OnLayoutErrorListener layoutErrorListener) {
        mContext = context;
        mResourceIds = resourceIds;
        mImageStore = imageStore;
        mLayoutErrorListener = layoutErrorListener;
    }

    /**
     * 解析eventbinding 事件,
     * <p>
     * 根据不同的 eventType 类型
     * 创建自定义的 EventTriggeringVisitor 对象(继承自ViewVisitor)
     * <p>
     * 存在四类 EventTriggeringVisitor
     * 1. click
     * 2. selected
     * 3. text_changed
     * 4. detected
     * <p>
     * <p>
     * source 包含 event_name event_type  path target_activity
     *
     * @param source
     * @param listener DynamicEventTracker
     * @return
     * @throws BadInstructionsException
     */
    public ViewVisitor readEventBinding(JSONObject source,
                                        ViewVisitor.OnEventListener listener) throws BadInstructionsException {
        try {
            //事件名称
            final String eventName = source.getString("event_name");
            //事件类型
            final String eventType = source.getString("event_type");
            //路径
            final JSONArray pathDesc = source.getJSONArray("path");
            // 解析路径,Json转换成 PathElement
            // 每条 event 事件 会对应多个 PathElement
            final List<Pathfinder.PathElement> path = readPath(pathDesc, mResourceIds);
            //解析出来的路径如果为0,则不可能绑定到UI ,所以抛出异常
            if (path.size() == 0) {
                throw new InapplicableInstructionsException("event '" + eventName + "' will not be bound to any element in the UI.");
            }

            if ("click".equals(eventType)) {
                return new ViewVisitor.AddAccessibilityEventVisitor(
                        path,
                        AccessibilityEvent.TYPE_VIEW_CLICKED,
                        eventName,
                        listener
                );
            } else if ("selected".equals(eventType)) {
                return new ViewVisitor.AddAccessibilityEventVisitor(
                        path,
                        AccessibilityEvent.TYPE_VIEW_SELECTED,
                        eventName,
                        listener
                );
            } else if ("text_changed".equals(eventType)) {
                return new ViewVisitor.AddTextChangeListener(
                        path,
                        eventName,
                        listener);
            } else if ("detected".equals(eventType)) {
                return new ViewVisitor.ViewDetectorVisitor(
                        path,
                        eventName,
                        listener);
            } else {
                //无法抓取除了 click selected text_changed detected 之外类型的事件
                throw new BadInstructionsException("Mixpanel can't track event type \"" + eventType + "\"");
            }
        } catch (final JSONException e) {
            throw new BadInstructionsException("Can't interpret instructions due to JSONException", e);
        }
    }

    public Edit readEdit(JSONObject source) throws BadInstructionsException, CantGetEditAssetsException {
        final ViewVisitor visitor;
        final List<String> assetsLoaded = new ArrayList<String>();

        try {
            final JSONArray pathDesc = source.getJSONArray("path");
            final List<Pathfinder.PathElement> path = readPath(pathDesc, mResourceIds);

            if (path.size() == 0) {
                throw new InapplicableInstructionsException("Edit will not be bound to any element in the UI.");
            }

            if (source.getString("change_type").equals("property")) {
                final JSONObject propertyDesc = source.getJSONObject("property");
                final String targetClassName = propertyDesc.getString("classname");
                if (null == targetClassName) {
                    throw new BadInstructionsException("Can't bind an edit property without a target class");
                }

                final Class<?> targetClass;
                try {
                    targetClass = Class.forName(targetClassName);
                } catch (final ClassNotFoundException e) {
                    throw new BadInstructionsException("Can't find class for visit path: " + targetClassName, e);
                }

                final PropertyDescription prop = readPropertyDescription(targetClass, source.getJSONObject("property"));
                final JSONArray argsAndTypes = source.getJSONArray("args");
                final Object[] methodArgs = new Object[argsAndTypes.length()];
                for (int i = 0; i < argsAndTypes.length(); i++) {
                    final JSONArray argPlusType = argsAndTypes.getJSONArray(i);
                    final Object jsonArg = argPlusType.get(0);
                    final String argType = argPlusType.getString(1);
                    methodArgs[i] = convertArgument(jsonArg, argType, assetsLoaded);
                }

                final Caller mutator = prop.makeMutator(methodArgs);
                if (null == mutator) {
                    throw new BadInstructionsException("Can't update a read-only property " + prop.name + " (add a mutator to make this work)");
                }

                visitor = new ViewVisitor.PropertySetVisitor(path, mutator, prop.accessor);
            } else if (source.getString("change_type").equals("layout")) {
                final JSONArray args = source.getJSONArray("args");
                ArrayList<ViewVisitor.LayoutRule> newParams = new ArrayList<ViewVisitor.LayoutRule>();
                int length = args.length();
                for (int i = 0; i < length; i++) {
                    JSONObject layout_info = args.optJSONObject(i);
                    ViewVisitor.LayoutRule params;

                    final String view_id_name = layout_info.getString("view_id_name");
                    final String anchor_id_name = layout_info.getString("anchor_id_name");
                    final Integer view_id = reconcileIds(-1, view_id_name, mResourceIds);
                    final Integer anchor_id;
                    if (anchor_id_name.equals("0")) {
                        anchor_id = 0;
                    } else if (anchor_id_name.equals("-1")) {
                        anchor_id = RelativeLayout.TRUE;
                    } else {
                        anchor_id = reconcileIds(-1, anchor_id_name, mResourceIds);
                    }

                    if (view_id == null || anchor_id == null) {
                        MPLog.w(LOGTAG, "View (" + view_id_name + ") or anchor (" + anchor_id_name + ") not found.");
                        continue;
                    }

                    params = new ViewVisitor.LayoutRule(view_id, layout_info.getInt("verb"), anchor_id);
                    newParams.add(params);
                }
                visitor = new ViewVisitor.LayoutUpdateVisitor(path, newParams, source.getString("name"), mLayoutErrorListener);
            } else {
                throw new BadInstructionsException("Can't figure out the edit type");
            }
        } catch (final NoSuchMethodException e) {
            throw new BadInstructionsException("Can't create property mutator", e);
        } catch (final JSONException e) {
            throw new BadInstructionsException("Can't interpret instructions due to JSONException", e);
        }

        return new Edit(visitor, assetsLoaded);
    }


    /**
     * 解析传入的 source(服务端的配置信息)
     * <p>
     * 配置信息中包含了目标类 (即要抓取某种类型的View,每个View哪些属性要被抓取 )
     * 获取服务端下发的配置文件中 对属性的描述
     * 客户端会通过这些描述 去针对指定的控件 获取指定的属性信息 或者设置属性信息
     * <p>
     * set/get 方法会保存到所生成的PropertyDescription 对象中
     * <p>
     * 会将待抓取的控件的信息解析之后,保存到ViewSnapshot 类中
     *
     * @param source
     * @return
     * @throws BadInstructionsException
     */
    public ViewSnapshot readSnapshotConfig(JSONObject source) throws BadInstructionsException {
        // 保存由配置文件中解析出来的 待抓取控件的相关信息
        final List<PropertyDescription> properties = new ArrayList<PropertyDescription>();

        try {
            final JSONObject config = source.getJSONObject("config");
            //包含目标类的 数组
            final JSONArray classes = config.getJSONArray("classes");
            for (int classIx = 0; classIx < classes.length(); classIx++) {
                final JSONObject classDesc = classes.getJSONObject(classIx);
                //目标View的 类名
                final String targetClassName = classDesc.getString("name");
                //获取目标View的字节码
                final Class<?> targetClass = Class.forName(targetClassName);
                //获取目标View的一些属性,根据不同的View类型,会下发不同的属性要求
                //importantForAccessibility,clickable,alpha,hidden,setVisibility,background等
                final JSONArray propertyDescs = classDesc.getJSONArray("properties");
                // 遍历每一个具体的属性, 去获取其 set/get 等方法的相关信息
                for (int i = 0; i < propertyDescs.length(); i++) {
                    final JSONObject propertyDesc = propertyDescs.getJSONObject(i);
                    //封装这个具体属性的 get 或者set信息
                    final PropertyDescription desc = readPropertyDescription(targetClass, propertyDesc);
                    properties.add(desc);
                }
            }

            return new ViewSnapshot(mContext, properties, mResourceIds);
        } catch (JSONException e) {
            throw new BadInstructionsException("Can't read snapshot configuration", e);
        } catch (final ClassNotFoundException e) {
            throw new BadInstructionsException("Can't resolve types for snapshot configuration", e);
        }
    }

    public MPPair<String, Object> readTweak(JSONObject tweakDesc) throws BadInstructionsException {
        try {
            final String tweakName = tweakDesc.getString("name");
            final String type = tweakDesc.getString("type");
            Object value;
            if ("number".equals(type)) {
                final String encoding = tweakDesc.getString("encoding");
                if ("d".equals(encoding)) {
                    value = tweakDesc.getDouble("value");
                } else if ("l".equals(encoding)) {
                    value = tweakDesc.getLong("value");
                } else {
                    throw new BadInstructionsException("number must have encoding of type \"l\" for long or \"d\" for double in: " + tweakDesc);
                }
            } else if ("boolean".equals(type)) {
                value = tweakDesc.getBoolean("value");
            } else if ("string".equals(type)) {
                value = tweakDesc.getString("value");
            } else {
                throw new BadInstructionsException("Unrecognized tweak type " + type + " in: " + tweakDesc);
            }

            return new MPPair<String, Object>(tweakName, value);
        } catch (JSONException e) {
            throw new BadInstructionsException("Can't read tweak update", e);
        }
    }

    // Package access FOR TESTING ONLY
    /* package */

    /**
     * @param pathDesc
     * @param idNameToId
     * @return
     * @throws JSONException
     */
    List<Pathfinder.PathElement> readPath(JSONArray pathDesc,
                                          ResourceIds idNameToId) throws JSONException {
        final List<Pathfinder.PathElement> path = new ArrayList<Pathfinder.PathElement>();

        for (int i = 0; i < pathDesc.length(); i++) {
            final JSONObject targetView = pathDesc.getJSONObject(i);

            final String prefixCode = JSONUtils.optionalStringKey(targetView, "prefix");
            final String targetViewClass = JSONUtils.optionalStringKey(targetView, "view_class");
            final int targetIndex = targetView.optInt("index", -1);
            final String targetDescription = JSONUtils.optionalStringKey(targetView, "contentDescription");
            //获取路径中的ID信息
            final int targetExplicitId = targetView.optInt("id", -1);
            //获取路径中的ID对应的名称
            final String targetIdName = JSONUtils.optionalStringKey(targetView, "mp_id_name");
            final String targetTag = JSONUtils.optionalStringKey(targetView, "tag");

            //将prefixCode 转换成 prefix
            final int prefix;
            if ("shortest".equals(prefixCode)) {
                prefix = Pathfinder.PathElement.SHORTEST_PREFIX;
            } else if (null == prefixCode) {
                prefix = Pathfinder.PathElement.ZERO_LENGTH_PREFIX;
            } else {
                MPLog.w(LOGTAG, "Unrecognized prefix type \"" + prefixCode + "\"." +
                        " No views will be matched");
                //永远不可能匹配的code
                return NEVER_MATCH_PATH;
            }

            final int targetId;
            // 根据id 或者id-name  去寻找 id
            final Integer targetIdOrNull = reconcileIds(targetExplicitId,
                    targetIdName,
                    idNameToId);
            //如果id 都为空 ,则说明不可能找的到对应的控件
            // 只有存在id的情况下,才能去匹配控件
            if (null == targetIdOrNull) {
                return NEVER_MATCH_PATH;
            } else {
                targetId = targetIdOrNull.intValue();
            }

            path.add(new Pathfinder.PathElement(prefix, targetViewClass,
                    targetIndex, targetId, targetDescription, targetTag));
        }

        return path;
    }

    // May return null (and log a warning) if arguments cannot be reconciled\

    /**
     * @param explicitId id
     * @param idName     id名称
     * @param idNameToId
     * @return
     */
    private Integer reconcileIds(int explicitId, String idName, ResourceIds idNameToId) {
        final int idFromName;
        if (null != idName) {
            //判断是否存在idName对应的id
            if (idNameToId.knownIdName(idName)) {
                // 通过idname 获取到 id
                idFromName = idNameToId.idFromName(idName);
            } else {
                MPLog.w(LOGTAG,
                        "Path element contains an id name not known to the system. No views will be matched.\n" +
                                "Make sure that you're not stripping your packages R class out with proguard.\n" +
                                "id name was \"" + idName + "\""
                );
                //如果存在idName 没有对应的id ,那么 说明这个控件不可能被匹配到
                return null;
            }
        } else {
            idFromName = -1;
        }
        // 通过id名称获取到的ID 不为空
        // 下发数据中的id 不为空
        // 俩者不相同 ,则说明这个控件不可能被找到
        if (-1 != idFromName && -1 != explicitId && idFromName != explicitId) {
            MPLog.e(LOGTAG, "Path contains both a named and an explicit id, and they don't match. No views will be matched.");
            return null;
        }

        if (-1 != idFromName) {
            return idFromName;
        }

        return explicitId;
    }

    /**
     * 解析配置文件中 指定控件的待抓取信息
     * <p>
     * 与get方法有关的信息会保存到 Caller 类对象中
     * <p>
     * 与set方法有关的信息 目前只有一个 方法名称..
     * <p>
     * 会将get/set 相关信息 最终 保存到 PropertyDescription 对象中,并返回
     *
     * @param targetClass  目标View的字节码
     * @param propertyDesc 属性描述
     * @return
     * @throws BadInstructionsException
     */
    private PropertyDescription readPropertyDescription(Class<?> targetClass, JSONObject propertyDesc)
            throws BadInstructionsException {
        try {
            //属性名称
            final String propName = propertyDesc.getString("name");

            Caller accessor = null;
            // 判断是否存在get方法
            if (propertyDesc.has("get")) {
                final JSONObject accessorConfig = propertyDesc.getJSONObject("get");
                // get方法名称
                final String accessorName = accessorConfig.getString("selector");
                // get方法 返回值类型
                final String accessorResultTypeName = accessorConfig.getJSONObject("result").getString("type");
                final Class<?> accessorResultType = Class.forName(accessorResultTypeName);
                accessor = new Caller(
                        targetClass,
                        accessorName,
                        NO_PARAMS,
                        accessorResultType);
            }
            // 更改器名称, 即 set方法的名称
            final String mutatorName;
            // 判断是否存在set方法
            if (propertyDesc.has("set")) {
                //取出set方法
                final JSONObject mutatorConfig = propertyDesc.getJSONObject("set");
                //获取set方法名称
                mutatorName = mutatorConfig.getString("selector");
            } else {
                mutatorName = null;
            }

            return new PropertyDescription(propName, targetClass, accessor, mutatorName);
        } catch (final NoSuchMethodException e) {
            throw new BadInstructionsException("Can't create property reader", e);
        } catch (final JSONException e) {
            throw new BadInstructionsException("Can't read property JSON", e);
        } catch (final ClassNotFoundException e) {
            throw new BadInstructionsException("Can't read property JSON, relevant arg/return class not found", e);
        }
    }

    private Object convertArgument(Object jsonArgument, String type, List<String> assetsLoaded)
            throws BadInstructionsException, CantGetEditAssetsException {
        // Object is a Boolean, JSONArray, JSONObject, Number, String, or JSONObject.NULL
        try {
            if ("java.lang.CharSequence".equals(type)) { // Because we're assignable
                return jsonArgument;
            } else if ("boolean".equals(type) || "java.lang.Boolean".equals(type)) {
                return jsonArgument;
            } else if ("int".equals(type) || "java.lang.Integer".equals(type)) {
                return ((Number) jsonArgument).intValue();
            } else if ("float".equals(type) || "java.lang.Float".equals(type)) {
                return ((Number) jsonArgument).floatValue();
            } else if ("android.graphics.drawable.Drawable".equals(type)) {
                // For historical reasons, we attempt to interpret generic Drawables as BitmapDrawables
                return readBitmapDrawable((JSONObject) jsonArgument, assetsLoaded);
            } else if ("android.graphics.drawable.BitmapDrawable".equals(type)) {
                return readBitmapDrawable((JSONObject) jsonArgument, assetsLoaded);
            } else if ("android.graphics.drawable.ColorDrawable".equals(type)) {
                int colorValue = ((Number) jsonArgument).intValue();
                return new ColorDrawable(colorValue);
            } else {
                throw new BadInstructionsException("Don't know how to interpret type " + type + " (arg was " + jsonArgument + ")");
            }
        } catch (final ClassCastException e) {
            throw new BadInstructionsException("Couldn't interpret <" + jsonArgument + "> as " + type);
        }
    }

    private Drawable readBitmapDrawable(JSONObject description, List<String> assetsLoaded)
            throws BadInstructionsException, CantGetEditAssetsException {
        try {
            if (description.isNull("url")) {
                throw new BadInstructionsException("Can't construct a BitmapDrawable with a null url");
            }

            final String url = description.getString("url");

            final boolean useBounds;
            final int left;
            final int right;
            final int top;
            final int bottom;
            if (description.isNull("dimensions")) {
                left = right = top = bottom = 0;
                useBounds = false;
            } else {
                final JSONObject dimensions = description.getJSONObject("dimensions");
                left = dimensions.getInt("left");
                right = dimensions.getInt("right");
                top = dimensions.getInt("top");
                bottom = dimensions.getInt("bottom");
                useBounds = true;
            }

            final Bitmap image;
            try {
                image = mImageStore.getImage(url);
                assetsLoaded.add(url);
            } catch (ImageStore.CantGetImageException e) {
                throw new CantGetEditAssetsException(e.getMessage(), e.getCause());
            }

            final Drawable ret = new BitmapDrawable(Resources.getSystem(), image);
            if (useBounds) {
                ret.setBounds(left, top, right, bottom);
            }

            return ret;
        } catch (JSONException e) {
            throw new BadInstructionsException("Couldn't read drawable description", e);
        }
    }

    private final Context mContext;
    private final ResourceIds mResourceIds;
    private final ImageStore mImageStore;
    private final ViewVisitor.OnLayoutErrorListener mLayoutErrorListener;

    private static final Class<?>[] NO_PARAMS = new Class[0];
    private static final List<Pathfinder.PathElement> NEVER_MATCH_PATH = Collections.<Pathfinder.PathElement>emptyList();

    @SuppressWarnings("unused")
    private static final String LOGTAG = "MixpanelAPI.EProtocol";
} // EditProtocol
