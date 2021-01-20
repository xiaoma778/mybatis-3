/**
 * Copyright 2009-2015 the original author or authors.
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

/**
 * Mapper 配置文件中的 SQL 节点中的 SQL 语句中定义的动态 SQL 节点、文本节点等由该接口表示
 * 如：where、choose、if、foreach 等节点
 * SqlNode 有多个实现类，每个实现类对应一个动态 SQL 节点
 * @author Clinton Begin
 */
public interface SqlNode {

    /**
     * 该方法是 SqlNode 接口中定义的唯一方法，该方法会根据用户传入的实参，参数解析该 SqlNode 所记录的动态
     * SQL 节点，并调用 DynamicContext.appendSql() 方法将解析后的 SQL 片段追加到 DynamicContext.sqlBuilder
     * 中保存。当 SQL 节点下的所有 SqlNode 完成解析后，我们就可以从 DynamicContext 中获取一条动态生成的、
     * 完整的 SQL 语句
     * @param context
     * @return
     */
    boolean apply(DynamicContext context);
}
