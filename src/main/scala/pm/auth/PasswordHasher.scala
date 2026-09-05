package pm.auth

import org.mindrot.jbcrypt.BCrypt

/** bcrypt hashing — the single code path used by registration, the seeder and
  * login verification so hashes are always mutually compatible.
  */
object PasswordHasher {
  def hash(plain: String): String =
    BCrypt.hashpw(plain, BCrypt.gensalt(10))

  def verify(plain: String, hash: String): Boolean =
    try BCrypt.checkpw(plain, hash)
    catch { case _: Exception => false }
}
