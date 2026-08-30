package com.android.string.plugin;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注的类、方法或字段内的字符串常量不参与加密，保持明文。
 * <p>
 * 字段级仅对 String 字段生效；Kotlin 属性注解会自动落到 backing field 上，
 * 无 backing field 的属性需使用 @get:KeepString。
 *
 * @author chancey
 * @date 2026/8/30
 **/
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@Retention(RetentionPolicy.CLASS)
public @interface KeepString {
}
