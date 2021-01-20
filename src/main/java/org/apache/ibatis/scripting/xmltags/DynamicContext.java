/**
 * Copyright 2009-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ibatis.scripting.xmltags;

import java.util.HashMap;
import java.util.Map;
import java.util.StringJoiner;

import ognl.OgnlContext;
import ognl.OgnlRuntime;
import ognl.PropertyAccessor;

import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.Configuration;

/**
 * 记录解析动态 SQL 语句之后产生的 SQL 语句片段，可以认为它是一个用于记录动态 SQL 语句解析结果的容器
 * @author Clinton Begin
 */
public class DynamicContext {

    public static final String PARAMETER_OBJECT_KEY = "_parameter";
    public static final String DATABASE_ID_KEY = "_databaseId";

    static {
        OgnlRuntime.setPropertyAccessor(ContextMap.class, new ContextAccessor());
    }

    /** 参数上下文*/
    private final ContextMap bindings;
    /** 在 SqlNode 解析动态 SQL 时，会将解析后的 SQL 语句片段添加到该属性中保存，最终拼凑出一条完成的 SQL 语句*/
    private final StringJoiner sqlBuilder = new StringJoiner(" ");
    private int uniqueNumber = 0;

    /**
     * 构造函数
     * @param configuration mybatis-config.xml 配置信息
     * @param parameterObject 运行时用户传入的参数，其中包含了后续用于替换 "#{}" 占位符的实参
     */
    public DynamicContext(Configuration configuration, Object parameterObject) {
        // 对于非 Map 类型的参数，会创建对应的 MetaObject 对象，并封装成 ContextMap 对象（ContextMap 其实也是一个 Map 对象）
        if (parameterObject != null && !(parameterObject instanceof Map)) {
            MetaObject metaObject = configuration.newMetaObject(parameterObject);
            boolean existsTypeHandler = configuration.getTypeHandlerRegistry().hasTypeHandler(parameterObject.getClass());
            bindings = new ContextMap(metaObject, existsTypeHandler);
        } else {
            bindings = new ContextMap(null, false);
        }
        // 将 PARAMETER_OBJECT_KEY -> parameterObject 这一对应关系添加到 bindings 集合中，其
        // 中 PARAMETER_OBJECT_KEY 的值是 "_parameter"，在有的 SqlNode 实现中直接使用了该字面值
        bindings.put(PARAMETER_OBJECT_KEY, parameterObject);
        bindings.put(DATABASE_ID_KEY, configuration.getDatabaseId());
    }

    public Map<String, Object> getBindings() {
        return bindings;
    }

    public void bind(String name, Object value) {
        bindings.put(name, value);
    }

    public void appendSql(String sql) {
        sqlBuilder.add(sql);
    }

    /**
     * 获取解析后的、完整的 SQL 语句
     * @return
     */
    public String getSql() {
        return sqlBuilder.toString().trim();
    }

    public int getUniqueNumber() {
        return uniqueNumber++;
    }

    /**
     * 继承自 HashMap 并重写了它的 get() 方法
     */
    static class ContextMap extends HashMap<String, Object> {
        private static final long serialVersionUID = 2977601501966151582L;
        /** 将用户传入的参数封装成了 MetaObject 对象*/
        private final MetaObject parameterMetaObject;
        private final boolean fallbackParameterObject;

        public ContextMap(MetaObject parameterMetaObject, boolean fallbackParameterObject) {
            this.parameterMetaObject = parameterMetaObject;
            this.fallbackParameterObject = fallbackParameterObject;
        }

        @Override
        public Object get(Object key) {
            String strKey = (String)key;
            if (super.containsKey(strKey)) {
                return super.get(strKey);
            }

            if (parameterMetaObject == null) {
                return null;
            }

            // 没有 strKey 属性
            if (fallbackParameterObject && !parameterMetaObject.hasGetter(strKey)) {
                return parameterMetaObject.getOriginalObject();
            } else {
                // issue #61 do not modify the context when reading
                // 从运行时参数中查找对应属性
                return parameterMetaObject.getValue(strKey);
            }
        }
    }

    static class ContextAccessor implements PropertyAccessor {

        @Override
        public Object getProperty(Map context, Object target, Object name) {
            Map map = (Map)target;

            Object result = map.get(name);
            if (map.containsKey(name) || result != null) {
                return result;
            }

            Object parameterObject = map.get(PARAMETER_OBJECT_KEY);
            if (parameterObject instanceof Map) {
                return ((Map)parameterObject).get(name);
            }

            return null;
        }

        @Override
        public void setProperty(Map context, Object target, Object name, Object value) {
            Map<Object, Object> map = (Map<Object, Object>)target;
            map.put(name, value);
        }

        @Override
        public String getSourceAccessor(OgnlContext arg0, Object arg1, Object arg2) {
            return null;
        }

        @Override
        public String getSourceSetter(OgnlContext arg0, Object arg1, Object arg2) {
            return null;
        }
    }
}
