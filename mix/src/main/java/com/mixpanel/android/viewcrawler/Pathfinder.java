package com.mixpanel.android.viewcrawler;

import android.view.View;
import android.view.ViewGroup;

import com.mixpanel.android.util.MPLog;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

/**
 * Paths in the view hierarchy, and the machinery for finding views using them.
 * <p>
 * An individual pathfinder is NOT THREAD SAFE, and should only be used by one thread at a time.
 */
/* package */ class Pathfinder {

    /**
     * a path element E matches a view V if each non "prefix" or "index"
     * attribute of E is equal to (or characteristic of) V.
     * <p>
     * So
     * <p>
     * E.viewClassName == 'com.mixpanel.Awesome' => V instanceof com.mixpanelAwesome
     * E.id == 123 => V.getId() == 123
     * <p>
     * The index attribute, counting from root to leaf, and first child to last child, selects a particular
     * matching view amongst all possible matches. Indexing starts at zero, like an array
     * index. So E.index == 2 means "Select the third possible match for this element"
     * <p>
     * The prefix attribute refers to the position of the matched views in the hierarchy,
     * relative to the current position of the path being searched. The "current position" of
     * a path element is determined by the path that preceeded that element:
     * <p>
     * - The current position of the empty path is the root view
     * <p>
     * - The current position of a non-empty path is the children of any element that matched the last
     * element of that path.
     * <p>
     * Prefix values can be:
     * <p>
     * ZERO_LENGTH_PREFIX- the next match must occur at the current position (so at the root
     * view if this is the first element of a path, or at the matching children of the views
     * already matched by the preceeding portion of the path.) If a path element with ZERO_LENGTH_PREFIX
     * has no index, then *all* matching elements of the path will be matched, otherwise indeces
     * will count from first child to last child.
     * <p>
     * SHORTEST_PREFIX- the next match must occur at some descendant of the current position.
     * SHORTEST_PREFIX elements are indexed depth-first, first child to last child. For performance
     * reasons, at most one element will ever be matched to a SHORTEST_PREFIX element, so
     * elements with no index will be treated as having index == 0
     */
    public static class PathElement {
        public PathElement(int usePrefix, String vClass, int ix, int vId, String cDesc, String vTag) {
            prefix = usePrefix;
            viewClassName = vClass;
            index = ix;
            viewId = vId;
            contentDescription = cDesc;
            tag = vTag;
        }

        @Override
        public String toString() {
            try {
                final JSONObject ret = new JSONObject();
                if (prefix == SHORTEST_PREFIX) {
                    ret.put("prefix", "shortest");
                }
                if (null != viewClassName) {
                    ret.put("view_class", viewClassName);
                }
                if (index > -1) {
                    ret.put("index", index);
                }
                if (viewId > -1) {
                    ret.put("id", viewId);
                }
                if (null != contentDescription) {
                    ret.put("contentDescription", contentDescription);
                }
                if (null != tag) {
                    ret.put("tag", tag);
                }
                return ret.toString();
            } catch (final JSONException e) {
                throw new RuntimeException("Can't serialize PathElement to String", e);
            }
        }

        /**
         * 前缀 SHORTEST_PREFIX,ZERO_LENGTH_PREFIX
         */
        public final int prefix;
        /**
         * View所属的类名
         */
        public final String viewClassName;
        /**
         *
         */
        public final int index;
        /**
         * View的ID
         */
        public final int viewId;
        public final String contentDescription;
        public final String tag;

        public static final int ZERO_LENGTH_PREFIX = 0;
        public static final int SHORTEST_PREFIX = 1;
    }

    public interface Accumulator {
        public void accumulate(View v);
    }

    public Pathfinder() {
        mIndexStack = new IntStack();
    }

    /**
     * @param givenRootView DecorView.getRootView
     * @param path
     * @param accumulator
     */
    public void findTargetsInRoot(View givenRootView,
                                  List<PathElement> path,
                                  Accumulator accumulator) {

        //Path必须得非空,不然没有意义
        if (path.isEmpty()) {
            return;
        }
        //同时进行 findTargetsInRoot 有限制 ,不能超过stack的最大容量256
        if (mIndexStack.full()) {
            MPLog.w(LOGTAG, "There appears to be a concurrency issue in the pathfinding code. Path will not be matched.");
            return; // No memory to perform the find.
        }
        // 获取Path的第一部分
        //{"prefix":"shortest","index":0,"id":16908290}
        //{"view_class":"android.support.constraint.ConstraintLayout","index":0}
        //{"index":0,"id":2131230759}
        final PathElement rootPathElement = path.get(0);
        //剩下的Path....
        final List<PathElement> childPath = path.subList(1, path.size());
        // 取出IndexStack 中未使用的一个元素来使用, 返回其index
        final int indexKey = mIndexStack.alloc();
        //Path第一部分 进行匹配
        final View rootView = findPrefixedMatch(rootPathElement, givenRootView, indexKey);
        // 释放 alloc 返回的那个index,
        // alloc 和 free 需要成对调用,并且一旦free调用了  alloc 返回的那个index 立马被当做无效
        mIndexStack.free();
        //如果第一部分存在了,那么接着下面的匹配
        if (null != rootView) {
            findTargetsInMatchedView(rootView, childPath, accumulator);
        }
    }

    /**
     * 在路径第一层匹配成功后返回的那个view中继续匹配接下来的Path
     *
     * @param alreadyMatched
     * @param remainingPath
     * @param accumulator
     */
    private void findTargetsInMatchedView(View alreadyMatched,
                                          List<PathElement> remainingPath,
                                          Accumulator accumulator) {
        // When this is run, alreadyMatched has already been matched to a path prefix.
        // path is a possibly empty "remaining path" suffix left over after the match
        // 剩余空,则说明已经匹配成功
        if (remainingPath.isEmpty()) {
            // Nothing left to match- we're found!
            // 对匹配成功的控件设置Delegate
            accumulator.accumulate(alreadyMatched);
            return;
        }

        //如果 第一层Path 匹配出来的View 不是ViewGroup,直接结束
        //如果不是ViewGroup 那么就没有子类了...
        if (!(alreadyMatched instanceof ViewGroup)) {
            // Matching a non-empty path suffix is impossible, because we have no children
            // 这种情况对应的是子路径为空了,因为匹配出来的View 本身就没有子类了!
            return;
        }
        // 判断路径深度!!! 如果已经达到256 直接gg
        if (mIndexStack.full()) {
            MPLog.v(LOGTAG, "Path is too deep, will not match");
            // Can't match anyhow, stack is too deep
            return;
        }

        final ViewGroup parent = (ViewGroup) alreadyMatched;
        final PathElement matchElement = remainingPath.get(0);
        final List<PathElement> nextPath = remainingPath.subList(1, remainingPath.size());

        final int childCount = parent.getChildCount();
        final int indexKey = mIndexStack.alloc();
        //遍历子类
        for (int i = 0; i < childCount; i++) {
            final View givenChild = parent.getChildAt(i);
            //找到符合路径规则的 view
            //这一步是进行匹配
            final View child = findPrefixedMatch(matchElement, givenChild, indexKey);
            if (null != child) {
                //这一步是进行下一层path的匹配,等待Path为空,直接设置Delegate
                findTargetsInMatchedView(child, nextPath, accumulator);
            }
            //如果child为空,则意味着第一个child不符合条件
            //这里用来接着是否 接着判断下一个子类
            //index 通常为0 ,所以第一个条件通常成立
            //然后会判断 stack[index]的位置 是否匹配成功?? 如果成功 那就直接结束循环了
            if (matchElement.index >= 0 && mIndexStack.read(indexKey) > matchElement.index) {
                break;
            }
        }
        //释放stack
        //与alloc 相对应!
        mIndexStack.free();
    }

    // Finds the first matching view of the path element in the given subject's view hierarchy.
    // If the path is indexed, it needs a start index, and will consume some indexes

    /**
     * 找到当前view视图中第一个匹配路径的控件
     *
     * @param findElement 路径,
     * @param subject     正常情况下是,DecorView
     * @param indexKey    Stack中分配给当前控件的index
     * @return
     */
    private View findPrefixedMatch(PathElement findElement, View subject, int indexKey) {
        //从 IndexStack 中获取 对应index的值,应该是0
        final int currentIndex = mIndexStack.read(indexKey);
        //找到匹配的 控件
        if (matches(findElement, subject)) {
            //匹配成功
            //指定index的 值++
            mIndexStack.increment(indexKey);
            // 过滤条件中的 index 值为-1 ,或者 与当前index相同 ,则返回当前控件
            if (findElement.index == -1 || findElement.index == currentIndex) {
                return subject;
            }
        }
        // 对路径的Prefix进行判断
        if (findElement.prefix == PathElement.SHORTEST_PREFIX &&
                subject instanceof ViewGroup) {
            //从其子类中找到 匹配 路径的 View并返回
            final ViewGroup group = (ViewGroup) subject;
            final int childCount = group.getChildCount();
            for (int i = 0; i < childCount; i++) {
                final View child = group.getChildAt(i);
                final View result = findPrefixedMatch(findElement, child, indexKey);
                if (null != result) {
                    return result;
                }
            }
        }
        // 匹配失败,返回NULL
        return null;
    }

    /**
     * 如果过滤条件中 存在 一下五个条件,那么就会对其进行判断,如果没有则不判断
     * className viewID contentDescription  TAG!
     * =====================================================
     * 一级过滤条件:
     * {"prefix":"shortest","index":0,"id":16908290}
     *
     * @param matchElement 过滤条件,即路径
     * @param subject      目标控件
     * @return
     */
    private boolean matches(PathElement matchElement, View subject) {
        // 过滤条件的viewClassName不为空
        // 目标控件的className 与 过滤条件的className 不相同
        if (null != matchElement.viewClassName &&
                !hasClassName(subject, matchElement.viewClassName)) {
            return false;
        }
        //过滤条件的viewID不能为-1
        //目标控件的viewId 与 过滤条件的VeiwID 不同
        if (-1 != matchElement.viewId && subject.getId() != matchElement.viewId) {
            return false;
        }

        //过滤条件的 contentDescription 不能为空
        //目标控件的 contentDescription 和 过滤条件的 contentDescription 不相等
        if (null != matchElement.contentDescription &&
                !matchElement.contentDescription.equals(subject.getContentDescription())) {
            return false;
        }

        //取出过滤条件中的 tag
        final String matchTag = matchElement.tag;
        // 过滤条件有tag时 才去进行匹配
        if (null != matchElement.tag) {
            //取出目标控件的tag
            final Object subjectTag = subject.getTag();
            //目标控件TAG 为空  或者  过滤条件和目标控件的TAG 不匹配
            if (null == subjectTag || !matchTag.equals(subject.getTag().toString())) {
                return false;
            }
        }

        return true;
    }

    /**
     * 对进行匹配的viwe,取其canonicalName 与目标className 进行匹配
     * <p>
     * 其父类匹配也算成功
     *
     * @param o         进行匹配的View
     * @param className 目标ClassName
     * @return
     */
    private static boolean hasClassName(Object o, String className) {
        Class<?> klass = o.getClass();
        while (true) {
            if (klass.getCanonicalName().equals(className)) {
                return true;
            }

            if (klass == Object.class) {
                return false;
            }

            klass = klass.getSuperclass();
        }
    }

    /**
     * Bargain-bin pool of integers, for use in avoiding allocations during path crawl
     * <p>
     * 貌似用来判断路径深度
     */
    private static class IntStack {
        public IntStack() {
            //初始化了 一个 大小为256 的int数组
            mStack = new int[MAX_INDEX_STACK_SIZE];
            mStackSize = 0;
        }

        public boolean full() {
            return mStack.length == mStackSize;
        }

        /**
         * Pushes a new value, and returns the index you can use to increment and read that value later.
         */
        public int alloc() {
            final int index = mStackSize;
            //栈大小自增
            mStackSize++;
            //Stack[index] 置0
            mStack[index] = 0;
            //返回分配出来的这个Stack[] 的index
            return index;
        }

        /**
         * Gets the value associated with index. index should be the result of a previous call to alloc()
         */
        public int read(int index) {
            return mStack[index];
        }

        public void increment(int index) {
            mStack[index]++;
        }

        /**
         * Should be matched to each call to alloc. Once free has been called, the key associated with the
         * matching alloc should be considered invalid.
         */
        public void free() {
            //栈大小-1
            mStackSize--;
            //如果栈大小 小于0 抛出异常
            if (mStackSize < 0) {
                throw new ArrayIndexOutOfBoundsException(mStackSize);
            }
        }

        private final int[] mStack;
        /**
         * 当前栈大小
         */
        private int mStackSize;
        /**
         * 最大栈大小
         */
        private static final int MAX_INDEX_STACK_SIZE = 256;
    }

    private final IntStack mIndexStack;

    @SuppressWarnings("unused")
    private static final String LOGTAG = "MixpanelAPI.PathFinder";
}
