package com.mixpanel.android.viewcrawler;

import android.view.View;

import com.mixpanel.android.util.MPLog;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/* package */ class Caller {


    /**
     * @param targetClass 目标View的字节码
     * @param methodName  需要调用的目标View 的方法
     * @param methodArgs  需要调用的目标View 的方法的参数
     * @param resultType  需要调用的目标View 的方法的返回值
     * @throws NoSuchMethodException
     */
    public Caller(Class<?> targetClass, String methodName, Object[] methodArgs, Class<?> resultType)
            throws NoSuchMethodException {
        mMethodName = methodName;

        // TODO if this is a bitmap, we might be hogging a lot of memory here.
        // We likely need a caching/loading to disk layer for bitmap-valued edits
        // I'm going to kick this down the road for now.
        mMethodArgs = methodArgs;
        mMethodResultType = resultType;
        //如果传入的methodArgs 不为空,会根据methdoArgs去targetClass找到匹配的method
        //如果为空,那么只会根据methodName去找到匹配的method
        mTargetMethod = pickMethod(targetClass);
        if (null == mTargetMethod) {
            throw new NoSuchMethodException("Method " + targetClass.getName() + "." + mMethodName + " doesn't exit");
        }

        mTargetClass = mTargetMethod.getDeclaringClass();
    }

    @Override
    public String toString() {
        return "[Caller " + mMethodName + "(" + mMethodArgs + ")" + "]";
    }

    public Object[] getArgs() {
        return mMethodArgs;
    }

    /**
     * 通过反射执行指定方法
     * @param target
     * @return
     */
    public Object applyMethod(View target) {
        return applyMethodWithArguments(target, mMethodArgs);
    }

    public Object applyMethodWithArguments(View target, Object[] arguments) {
        final Class<?> klass = target.getClass();
        if (mTargetClass.isAssignableFrom(klass)) {
            try {
                return mTargetMethod.invoke(target, arguments);
            } catch (final IllegalAccessException e) {
                MPLog.e(LOGTAG, "Method " + mTargetMethod.getName() + " appears not to be public", e);
            } catch (final IllegalArgumentException e) {
                MPLog.e(LOGTAG, "Method " + mTargetMethod.getName() + " called with arguments of the wrong type", e);
            } catch (final InvocationTargetException e) {
                MPLog.e(LOGTAG, "Method " + mTargetMethod.getName() + " threw an exception", e);
            }
        }

        return null;
    }

    public boolean argsAreApplicable(Object[] proposedArgs) {
        final Class<?>[] paramTypes = mTargetMethod.getParameterTypes();
        if (proposedArgs.length != paramTypes.length) {
            return false;
        }

        for (int i = 0; i < proposedArgs.length; i++) {
            final Class<?> paramType = assignableArgType(paramTypes[i]);
            if (null == proposedArgs[i]) {
                if (paramType == byte.class ||
                        paramType == short.class ||
                        paramType == int.class ||
                        paramType == long.class ||
                        paramType == float.class ||
                        paramType == double.class ||
                        paramType == boolean.class ||
                        paramType == char.class) {
                    return false;
                }
            } else {
                final Class<?> argumentType = assignableArgType(proposedArgs[i].getClass());
                if (!paramType.isAssignableFrom(argumentType)) {
                    return false;
                }
            }
        }

        return true;
    }

    private static Class<?> assignableArgType(Class<?> type) {
        // a.isAssignableFrom(b) only tests if b is a
        // subclass of a. It does not handle the autoboxing case,
        // i.e. when a is an int and b is an Integer, so we have
        // to make the Object types primitive types. When the
        // function is finally invoked, autoboxing will take
        // care of the the cast.
        if (type == Byte.class) {
            type = byte.class;
        } else if (type == Short.class) {
            type = short.class;
        } else if (type == Integer.class) {
            type = int.class;
        } else if (type == Long.class) {
            type = long.class;
        } else if (type == Float.class) {
            type = float.class;
        } else if (type == Double.class) {
            type = double.class;
        } else if (type == Boolean.class) {
            type = boolean.class;
        } else if (type == Character.class) {
            type = char.class;
        }

        return type;
    }

    private Method pickMethod(Class<?> klass) {
        // 配置文件中的 方法参数类型
        final Class<?>[] argumentTypes = new Class[mMethodArgs.length];
        for (int i = 0; i < mMethodArgs.length; i++) {
            argumentTypes[i] = mMethodArgs[i].getClass();
        }

        for (final Method method : klass.getMethods()) {
            final String foundName = method.getName();
            final Class<?>[] params = method.getParameterTypes();
            //方法名不对 或者 方法参数数量不对
            if (!foundName.equals(mMethodName) || params.length != mMethodArgs.length) {
                continue;
            }
            //先对 返回值类型进行比较

            // 将配置文件中返回值类型 进行拆箱处理,例如 Boolean -> boolean Integer->int
            final Class<?> assignType = assignableArgType(mMethodResultType);
            // 同样的 对获取到的每个Method的返回值 也进行同样的处理
            final Class<?> resultType = assignableArgType(method.getReturnType());
            // 判断 前者和后者是否相同,或者前者是后者的超类或接口
            if (!assignType.isAssignableFrom(resultType)) {
                continue;
            }
            //再对 方法类型进行比较
            boolean assignable = true;
            for (int i = 0; i < params.length && assignable; i++) {
                //配置文件中的参数类型
                final Class<?> argumentType = assignableArgType(argumentTypes[i]);
                //实际方法的参数类型
                final Class<?> paramType = assignableArgType(params[i]);
                assignable = paramType.isAssignableFrom(argumentType);
            }

            if (!assignable) {
                continue;
            }
            // 返回匹配成果的方法
            return method;
        }

        return null;
    }

    private final String mMethodName;
    private final Object[] mMethodArgs;
    private final Class<?> mMethodResultType;
    private final Class<?> mTargetClass;
    private final Method mTargetMethod;

    @SuppressWarnings("unused")
    private static final String LOGTAG = "MixpanelABTest.Caller";
}
