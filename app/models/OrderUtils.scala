/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package models

// FIXME Replace or rewrite: This was copied from private play.core.utils.CaseInsensitiveOrdered which was public before
object OrderUtils {

  /**
    * Case Insensitive Ordering. We first compare by length, then
    * use a case insensitive lexicographic order. This allows us to
    * use a much faster length comparison before we even start looking
    * at the content of the strings.
    */
  object CaseInsensitiveOrdered extends Ordering[String] {
    def compare(x: String, y: String): Int = {
      val xl = x.length
      val yl = y.length
      if (xl < yl) -1 else if (xl > yl) 1 else x.compareToIgnoreCase(y)
    }
  }

}
