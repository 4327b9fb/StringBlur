package com.android.string.plugin;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 强制加密标注范围内的字符串：类/方法级压过 minLength 与敏感 API 跳过；
 * 字段级仅对带 ConstantValue 的静态 String 常量生效。
 *
 * @author chancey
 * @date 2026/8/30
 **/
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@Retention(RetentionPolicy.CLASS)
public @interface EncryptString {
}
