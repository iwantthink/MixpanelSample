package com.mixpanel.android.viewcrawler;

/* package */ class PropertyDescription {

    /**
     *
     * 包含了控件信息以及控件的get/set方法的相关信息
     *
     * @param name        属性名称
     * @param targetClass 目标View的字节码
     * @param accessor    get方法的相关信息
     * @param mutatorName set方法名称
     */
    public PropertyDescription(String name, Class<?> targetClass, Caller accessor, String mutatorName) {
        this.name = name;
        this.targetClass = targetClass;
        this.accessor = accessor;

        mMutatorName = mutatorName;
    }

    public Caller makeMutator(Object[] methodArgs)
            throws NoSuchMethodException {
        if (null == mMutatorName) {
            return null;
        }

        return new Caller(this.targetClass, mMutatorName, methodArgs, Void.TYPE);
    }

    @Override
    public String toString() {
        return "[PropertyDescription " + name + "," + targetClass + ", " + accessor + "/" + mMutatorName + "]";
    }

    public final String name;
    public final Class<?> targetClass;
    public final Caller accessor;

    private final String mMutatorName;
}
