package com.kepler.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.kepler.queue.QueuePolicy;

/**
 * @author KimShen
 *
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Inherited
public @interface Queue {

	public QueuePolicy policy() default QueuePolicy.ABORT;

	public int queue() default 0;

	public int core() default 0;

	public int max() default 0;
}
