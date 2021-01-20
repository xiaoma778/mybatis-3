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
package org.apache.ibatis.cache.decorators;

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.ibatis.cache.Cache;

/**
 * Lru (least recently used) cache decorator.
 *
 * @author Clinton Begin
 */
public class LruCache implements Cache {

    private final Cache delegate;
    /** LinkedHashMap 类型对象，它是一个有序的 HashMap，用于记录 key 最近的使用情况*/
    private Map<Object, Object> keyMap;
    /** 记录最少被使用的缓存项的 key*/
    private Object eldestKey;

    public LruCache(Cache delegate) {
        this.delegate = delegate;
        setSize(1024);
    }

    @Override
    public String getId() {
        return delegate.getId();
    }

    @Override
    public int getSize() {
        return delegate.getSize();
    }

    /**
     * 重新设置缓存大小时，会重置 keyMap 字段
     * @param size
     */
    public void setSize(final int size) {
        //LinkedHashMap 构造函数的第三个参数(accessOrder)，true 表示该 LinkedHashMap 记录的顺序是访问顺序
        //也就是说 LinkedHashMap.get() 方法会改变其记录的顺序
        keyMap = new LinkedHashMap<Object, Object>(size, .75F, true) {
            private static final long serialVersionUID = 4267176411845948333L;

            /**
             * 当调用 LinkedHashMap.put() 方法时，会调用该方法
             * @param eldest
             * @return
             */
            @Override
            protected boolean removeEldestEntry(Map.Entry<Object, Object> eldest) {
                boolean tooBig = size() > size;
                if (tooBig) {//如果已达到缓存上限，则更新 eldestKey 字段，后面会删除该项
                    eldestKey = eldest.getKey();
                }
                return tooBig;
            }
        };
    }

    @Override
    public void putObject(Object key, Object value) {
        delegate.putObject(key, value);
        cycleKeyList(key);
    }

    @Override
    public Object getObject(Object key) {
        keyMap.get(key); //touch 修改 LinkedHashMap 的访问顺序
        return delegate.getObject(key);
    }

    @Override
    public Object removeObject(Object key) {
        return delegate.removeObject(key);
    }

    @Override
    public void clear() {
        delegate.clear();
        keyMap.clear();
    }

    private void cycleKeyList(Object key) {
        //会触发 LinkedHashMap.removeEldestEntry()方法，检测是否达到缓存上限
        keyMap.put(key, key);
        if (eldestKey != null) {
            delegate.removeObject(eldestKey);//删除最久未使用的缓存项
            eldestKey = null;
        }
    }
}
