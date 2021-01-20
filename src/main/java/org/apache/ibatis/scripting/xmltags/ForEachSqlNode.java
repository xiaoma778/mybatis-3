/**
 * Copyright 2009-2019 the original author or authors.
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

import java.util.Map;

import org.apache.ibatis.parsing.GenericTokenParser;
import org.apache.ibatis.session.Configuration;

/**
 * 在动态 SQL 语句中构建 IN 条件语句的时候，通常需要对一个集合进行迭代，MyBatis 提供了 foreach 标签实现
 * 该功能。在使用 foreach 标签迭代集合时，不仅可以使用集合的元素和索引值，还可以在循环开始之前或结束之后添
 * 加指定的字符串，也允许在迭代过程中添加指定的分隔符
 * @author Clinton Begin
 */
public class ForEachSqlNode implements SqlNode {
    public static final String ITEM_PREFIX = "__frch_";

    /** 用户判断循环的终止条件，构造方法中会创建该对象*/
    private final ExpressionEvaluator evaluator;

    /** 迭代的集合表达式*/
    private final String collectionExpression;

    /** 记录了改 ForEachSqlNode 节点的子节点*/
    private final SqlNode contents;

    /** 在循环开始前要添加的字符串*/
    private final String open;

    /** 在循环结束后要添加的字符串*/
    private final String close;

    /** 循环过程中，每项之间的分隔符*/
    private final String separator;

    /** 本次迭代的元素，若迭代集合是 Map 则 item 是值*/
    private final String item;

    /** 当前迭代的次数，若迭代集合是 Map 则 index 是键*/
    private final String index;

    /** mybatis-config.xml 配置对象*/
    private final Configuration configuration;

    public ForEachSqlNode(Configuration configuration, SqlNode contents, String collectionExpression, String index, String item, String open, String close, String separator) {
        this.evaluator = new ExpressionEvaluator();
        this.collectionExpression = collectionExpression;
        this.contents = contents;
        this.open = open;
        this.close = close;
        this.separator = separator;
        this.index = index;
        this.item = item;
        this.configuration = configuration;
    }

    /**
     * 该方法主要步骤如下：
     * 1.解析集合表达式，获取对应的实际参数
     * 2.在循环开始之前，添加 open 字段指定的字符串
     * 3.开始遍历集合，根据遍历的位置和是否指定分隔符。用 PrefixedContext 封装 DynamicContext
     * 4.调用 applyIndex() 方法将 index 添加到 DynamicContext.bindings 集合中，供后续解析使用
     * 5.调用 applyItem() 方法将 item 添加到 DynamicContext.bindings 集合中，供后续解析使用
     * 6.转换子节点中的 "#{}" 占位符，此步骤会将 PrefixedContext 封装成 FilteredDynamicContext，
     *      在追加子节点转换结果时，就会使用 FilteredDynamicContext.apply() 方法 "#{}" 占位符转
     *      换成 "#{__frch_...}" 的格式。返回步骤  3 继续循环
     * 7.循环结束后，调用 DynamicContext.appenSql() 方法添加 close 指定的字符串
     *
     * @param context 最后的解析结果会保存到该参数中
     * @return
     */
    @Override
    public boolean apply(DynamicContext context) {
        // 获取参数信息
        Map<String, Object> bindings = context.getBindings();
        // 步骤1：解析集合表达式对应的实际参数
        final Iterable<?> iterable = evaluator.evaluateIterable(collectionExpression, bindings);
        if (!iterable.iterator().hasNext()) {
            return true;
        }
        boolean first = true;
        // 步骤2：在循环开始之前，调用 DynamicContext.appendSql() 方法添加 open 字段指定的字符串
        applyOpen(context);
        int i = 0;
        for (Object o : iterable) {
            // 记录当前 DynamicContext 对象
            DynamicContext oldContext = context;
            // 步骤3：创建 PrefixedContext，并让 context 指向该 PrefixedContext 对象
            // 如果是集合第一项 或者 未指定分隔符，则将 PrefixedContext.prefix 初始化为空字符串
            if (first || separator == null) {
                context = new PrefixedContext(context, "");
            } else {// 如果指定了分隔符，则 PrefixedContext.prefix 初始化为指定分隔符
                context = new PrefixedContext(context, separator);
            }

            // uniqueNumber 从 0 开始，每次递增 1，用于转换生成新的 "#{}" 占位符名称
            int uniqueNumber = context.getUniqueNumber();
            // Issue #709
            // 如果集合是 Map 类型，将集合中 key 和 value 添加到 DynamicContext.bindings 集合中保存
            if (o instanceof Map.Entry) {
                @SuppressWarnings("unchecked")
                Map.Entry<Object, Object> mapEntry = (Map.Entry<Object, Object>)o;
                applyIndex(context, mapEntry.getKey(), uniqueNumber);// 步骤4
                applyItem(context, mapEntry.getValue(), uniqueNumber);// 步骤5
            } else {
                // 将集合中的索引和元素添加到 DynamicContext.bindings 集合中保存
                applyIndex(context, i, uniqueNumber);
                applyItem(context, o, uniqueNumber);
            }
            // 步骤6：调用子节点的 apply() 方法进行处理，注意，这里使用的 FilteredDynamicContext 对象
            contents.apply(new FilteredDynamicContext(configuration, context, index, item, uniqueNumber));
            if (first) {
                first = !((PrefixedContext)context).isPrefixApplied();
            }
            // 还原成原来的 context
            context = oldContext;
            i++;
        }
        // 步骤7：循环结束后，调用 DynamicContext.appendSql() 方法添加 close 指定的字符串
        applyClose(context);
        context.getBindings().remove(item);
        context.getBindings().remove(index);
        return true;
    }

    /**
     * 将 index 添加到 DynamicContext.bindings 集合中，供后续解析使用
     * 注意该方法以及后面的 applyItem() 方法的第三个参数 i，该值由 DynamicContext 产生，且在每个
     * DynamicContext对象的声明周期中是单调递增的
     * @param context
     * @param o
     * @param i
     */
    private void applyIndex(DynamicContext context, Object o, int i) {
        if (index != null) {
            context.bind(index, o);
            context.bind(itemizeItem(index, i), o);
        }
    }

    /**
     * 将 item 添加到 DynamicContext.bindings 集合中，供后续解析使用
     * @param context
     * @param o
     * @param i
     */
    private void applyItem(DynamicContext context, Object o, int i) {
        if (item != null) {
            // key 为 index，value 是集合元素
            context.bind(item, o);
            // 为 index 添加前缀和后缀形成新的 key
            context.bind(itemizeItem(item, i), o);
        }
    }

    private void applyOpen(DynamicContext context) {
        if (open != null) {
            context.appendSql(open);
        }
    }

    private void applyClose(DynamicContext context) {
        if (close != null) {
            context.appendSql(close);
        }
    }

    /**
     * 添加 "__frch_" 前缀和 i 后缀
     * @param item
     * @param i
     * @return
     */
    private static String itemizeItem(String item, int i) {
        return ITEM_PREFIX + item + "_" + i;
    }

    /**
     * 负责处理 "#{}" 占位符，但它并完全解析 "#{}" 占位符
     */
    private static class FilteredDynamicContext extends DynamicContext {

        private final DynamicContext delegate;

        /** 对应集合项在集合中的索引位置*/
        private final int index;

        /** 对应集合项的 index，参见 ForEachSqlNode.index*/
        private final String itemIndex;

        /** 对应集合项的 item ，参见 ForEachSqlNode.item*/
        private final String item;

        public FilteredDynamicContext(Configuration configuration, DynamicContext delegate, String itemIndex, String item, int i) {
            super(configuration, null);
            this.delegate = delegate;
            this.index = i;
            this.itemIndex = itemIndex;
            this.item = item;
        }

        @Override
        public Map<String, Object> getBindings() {
            return delegate.getBindings();
        }

        @Override
        public void bind(String name, Object value) {
            delegate.bind(name, value);
        }

        @Override
        public String getSql() {
            return delegate.getSql();
        }

        /**
         * 该方法：
         * 1.会将"#{item}" 占位符转换成 "#{__frch_item_1}" 的格式，其中 "__frch" 是固定的前缀，
         * "item" 与处理前的占位符一样，未发生改变，1 则是 FilteredDynamicContext产生的单调递增值；
         * <br/>
         * 2.还会将 "#{itemIndex}" 占位符转换成 "#{__frch_itemIndex_1}" 的格式，其中各个部分的
         * 含义同上
         * @param sql
         */
        @Override
        public void appendSql(String sql) {
            // 创建 GenericTokenParser 解析器，注意这里匿名实现的 TokenHandler 对象
            GenericTokenParser parser = new GenericTokenParser("#{", "}", content -> {
                // 对 item 进行处理
                String newContent = content.replaceFirst("^\\s*" + item + "(?![^.,:\\s])", itemizeItem(item, index));
                if (itemIndex != null && newContent.equals(content)) {
                    // 对 itemIndex 进行处理
                    newContent = content.replaceFirst("^\\s*" + itemIndex + "(?![^.,:\\s])", itemizeItem(itemIndex, index));
                }
                return "#{" + newContent + "}";
            });
            // 将解析后的 SQL 语句片段追加到 delegate 中
            delegate.appendSql(parser.parse(sql));
        }

        @Override
        public int getUniqueNumber() {
            return delegate.getUniqueNumber();
        }

    }

    private class PrefixedContext extends DynamicContext {
        private final DynamicContext delegate;
        // 知道的前缀
        private final String prefix;
        // 是否已经处理过前缀
        private boolean prefixApplied;

        public PrefixedContext(DynamicContext delegate, String prefix) {
            super(configuration, null);
            this.delegate = delegate;
            this.prefix = prefix;
            this.prefixApplied = false;
        }

        public boolean isPrefixApplied() {
            return prefixApplied;
        }

        @Override
        public Map<String, Object> getBindings() {
            return delegate.getBindings();
        }

        @Override
        public void bind(String name, Object value) {
            delegate.bind(name, value);
        }

        @Override
        public void appendSql(String sql) {
            // 判断是否需要追加前缀
            if (!prefixApplied && sql != null && sql.trim().length() > 0) {
                // 追加前缀
                delegate.appendSql(prefix);
                // 标识已经处理过前缀
                prefixApplied = true;
            }
            // 追加 sql 片段
            delegate.appendSql(sql);
        }

        @Override
        public String getSql() {
            return delegate.getSql();
        }

        @Override
        public int getUniqueNumber() {
            return delegate.getUniqueNumber();
        }
    }

}
