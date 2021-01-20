package org.apache.ibatis.reflection.property;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @Author: maqi
 * @Date: 2020-02-27 17:21
 * @Version 1.0
 */
class PropertyTokenizerTest {

    /**
     * 见 Mybatis技术内幕第二章 Page53
     */
    @Test
    void next() {
        String fullname = "orders[0].items[0].name";
        PropertyTokenizer tokenizer = null;
        do {
            tokenizer = tokenizer == null ? new PropertyTokenizer(fullname) : tokenizer.next();
            System.out.println(
                String.format("getIndexedName : %s, getName : %s, getIndex : %s, getChildren : %s",
                    tokenizer.getIndexedName(),
                    tokenizer.getName(),
                    tokenizer.getIndex(),
                    tokenizer.getChildren()
                )
            );
        } while (tokenizer.hasNext());
    }
}