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
package org.apache.ibatis.parsing;

/**
 * @Desc 通用的占位符解析器，它不仅可以用于默认值解析，还可用于动态 SQL 语句的解析
 * GenericTokenParser 只是查找指定的占位符，具体的解析行为会根据 TokenHandler 实现的不同而有所不同
 * @author Clinton Begin
 */
public class GenericTokenParser {

    private final String openToken;//占位符的开始标记
    private final String closeToken;//占位符的结束标记
    private final TokenHandler handler;//TokenHandler 接口的实现会按照一定的逻辑解析占位符

    public GenericTokenParser(String openToken, String closeToken, TokenHandler handler) {
        this.openToken = openToken;
        this.closeToken = closeToken;
        this.handler = handler;
    }

    /**
     * 顺序查找 openToken 和 closeToken，解析得到占位符的字面值，并将其交给 TokenHandler处理，然后将解析结果重新拼装成字符串并返回
     * 例：
     * 1.insert into Author (id,username,password,email,bio) values (${id},#{username},#{password},#{email},#{bio}) ---> 会将 ${id} 替换成具体的值
     * 2.insert into Author (id,username,password,email,bio) values (500,#{username},#{password},#{email},#{bio}) ---> 会将语句中的 "#{}" 占位符替换成 "?"
     * @param text
     * @return
     */
    public String parse(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        // search open token
        int start = text.indexOf(openToken);
        if (start == -1) {
            return text;
        }
        char[] src = text.toCharArray();
        int offset = 0;
        //用来记录解析后的字符串
        final StringBuilder builder = new StringBuilder();
        //用来记录一个占位符的字面值
        StringBuilder expression = null;
        while (start > -1) {
            if (start > 0 && src[start - 1] == '\\') {
                // this open token is escaped. remove the backslash and continue.
                builder.append(src, offset, start - offset - 1).append(openToken);
                offset = start + openToken.length();
            } else {
                // found open token. let's search close token.
                if (expression == null) {
                    expression = new StringBuilder();
                } else {
                    expression.setLength(0);
                }
                builder.append(src, offset, start - offset);
                offset = start + openToken.length();
                int end = text.indexOf(closeToken, offset);
                while (end > -1) {
                    if (end > offset && src[end - 1] == '\\') {
                        // this close token is escaped. remove the backslash and continue.
                        expression.append(src, offset, end - offset - 1).append(closeToken);
                        offset = end + closeToken.length();
                        end = text.indexOf(closeToken, offset);
                    } else {
                        expression.append(src, offset, end - offset);
                        break;
                    }
                }
                if (end == -1) {
                    // close token was not found.
                    builder.append(src, start, src.length - start);
                    offset = src.length;
                } else {
                    builder.append(handler.handleToken(expression.toString()));
                    offset = end + closeToken.length();
                }
            }
            start = text.indexOf(openToken, offset);
        }
        if (offset < src.length) {
            builder.append(src, offset, src.length - offset);
        }
        return builder.toString();
    }
}
