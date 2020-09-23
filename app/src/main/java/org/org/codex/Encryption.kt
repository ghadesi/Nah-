package org.org.codex

import android.content.Context
import android.security.keystore.KeyProperties
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import org.org.nahoft.Nahoft
import org.org.nahoft.Persist
import java.nio.ByteBuffer
import java.security.*
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.*
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.SecretKeySpec

// Note: The AndroidKeystore does not support ECDH key agreement between EC keys.
// The secure enclave does not appear to support EC keys at all, at this time.
// Therefore, we store keys in the EncryptedSharedPreferences instead of the KeyStore.
// This can be revised when the AndroidKeystore supports the required functionality.
class Encryption(val context: Context) {

    private val encryptionAlgorithm = "ChaCha20"
    private val paddingType = "PKCS1Padding"
    private val blockingMode = "NONE"
    private val ecGenParameterSpecName = "secp256r1"

    // Encrypted Shared Preferences
    private val privateKeyPreferencesKey = "NahoftPrivateKey"
    private val publicKeyPreferencesKey = "NahoftPublicKey"

    // Generate a new keypair for this device and store it in EncryptedSharedPreferences
    private fun generateKeypair(): KeyPair {

        // Generate ephemeral keypair and store using Android's KeyStore
        val keyPairGenerator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC)

        // Set our key properties
        val keyGenParameterSpec = ECGenParameterSpec(ecGenParameterSpecName)
        keyPairGenerator.initialize(keyGenParameterSpec)

        val keyPair = keyPairGenerator.generateKeyPair()

        val format = keyPair.public.format
        println(format)

        val encoded = keyPair.public.encoded
        println(encoded)

        // Save the keys to EncryptedSharedPreferences
        Persist.encryptedSharedPreferences
            .edit()
            .putString(privateKeyPreferencesKey, keyPair.private.encoded.toHexString())
            .putString(publicKeyPreferencesKey, keyPair.public.encoded.toHexString())
            .apply()

        val sharedPreferences = EncryptedSharedPreferences.create(
            "secret_shared_prefs",
            Persist.masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

        with (sharedPreferences.edit()) {
            putString(privateKeyPreferencesKey, keyPair.private.encoded.toHexString())
            putString(publicKeyPreferencesKey, keyPair.public.encoded.toHexString())
            commit()
        }

        return  keyPair
    }

    // Generate a new keypair for this device and store it in EncryptedSharedPreferences
    fun loadKeypair(): KeyPair? {

        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)

        val sharedPreferences = EncryptedSharedPreferences.create(
            "secret_shared_prefs",
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

        val privateKeyHex = sharedPreferences.getString(privateKeyPreferencesKey, null)
        val publicKeyHex = sharedPreferences.getString(publicKeyPreferencesKey, null)

        if (privateKeyHex == null || publicKeyHex == null) {
            return null
        }

        val privateKeyBytes = privateKeyHex.hexStringToByteArray()
        val publicKeyBytes = publicKeyHex.hexStringToByteArray()

        if (privateKeyBytes == null || publicKeyBytes == null) {
            return null
        }

        val publicKey = publicKeyFromByteArray(publicKeyBytes)
        val privateKey = privateKeyFromByteArray(privateKeyBytes)

        return KeyPair(publicKey, privateKey)

        return null
    }

    // Return the ECPublicKey from encoded raw bytes
    // The format of the encodedPublicKey is X.509
    fun publicKeyFromByteArray(encodedPublicKey: ByteArray): PublicKey? {
        val keyFactory = KeyFactory.getInstance(KeyProperties.KEY_ALGORITHM_EC)
        val keySpec = X509EncodedKeySpec(encodedPublicKey)

        return keyFactory.generatePublic(keySpec)
    }

    fun byteArrayFromPublicKey(publicKey: PublicKey): ByteArray {
        val keySpec = X509EncodedKeySpec(publicKey.encoded)
        return keySpec.encoded
    }

    fun privateKeyFromByteArray(encodedPrivateKey: ByteArray): PrivateKey? {
        val keySpec = X509EncodedKeySpec(encodedPrivateKey)
        val keyFactory = KeyFactory.getInstance(KeyProperties.KEY_ALGORITHM_EC)

        return keyFactory.generatePrivate(keySpec)
    }

    fun byteArrayFromPrivateKey(publicKey: PrivateKey): ByteArray {
        return publicKey.encoded
    }

    fun ensureKeysExist(): KeyPair {
        return generateKeypair()

        // FIXME: remove this hack, just for testing
//        val maybeKeyPair = loadKeypair()
//
//        if (maybeKeyPair != null) {
//            return  maybeKeyPair
//        } else {
//            return generateKeypair()
//        }
    }

    private fun getCipher(): Cipher? {
        return Cipher.getInstance(
            java.lang.String.format(
                "%s/%s/%s",
                encryptionAlgorithm,
                blockingMode,
                paddingType
            )
        )
    }

    fun encrypt(encodedPublicKey: ByteArray, plaintext: String): ByteArray? {
        val publicKey = publicKeyFromByteArray(encodedPublicKey)

        if (publicKey != null) {
            val derivedKey = getDerivedKey(publicKey)

            if (derivedKey != null) {
                val cipher = getCipher()

                if (cipher != null) {
                    cipher.init(Cipher.ENCRYPT_MODE, derivedKey)
                    return cipher.doFinal(plaintext.toByteArray())
                }
            }
        } else {
            print("Failed to encrypt a message, Friend's public key could not be decoded.")
            return null
        }

        return null
    }

    fun decrypt(friendPublicKey: PublicKey, ciphertext: ByteArray): String? {

        val derivedKey = getDerivedKey(friendPublicKey)

        if (derivedKey != null) {
            val cipher = getCipher()

            if (cipher != null) {
                cipher.init(Cipher.DECRYPT_MODE, derivedKey)
                return String(cipher.doFinal(ciphertext))
            }
        }

        return null
    }

    fun getDerivedKey(friendPublicKey: PublicKey): Key? {
        // Perform key agreement
        val keypair = ensureKeysExist()
        val privateKey = keypair.private
        val publicKey = keypair.public

        privateKey?.let {
            publicKey?.let {
                val keyAgreement: KeyAgreement = KeyAgreement.getInstance("ECDH")
                keyAgreement.init(privateKey)
                keyAgreement.doPhase(friendPublicKey, true)

                // Read shared secret
                val sharedSecret: ByteArray = keyAgreement.generateSecret()

                // Derive a key from the shared secret and both public keys
                val hash = MessageDigest.getInstance("SHA-256")
                hash.update(sharedSecret)

                // Simple deterministic ordering
                val keys: List<ByteBuffer> = Arrays.asList(
                    ByteBuffer.wrap(publicKey.encoded), ByteBuffer.wrap(
                        friendPublicKey.encoded
                    )
                )
                Collections.sort(keys)
                hash.update(keys[0])
                hash.update(keys[1])
                val derivedKeyBytes = hash.digest()
                val derivedKey: Key = SecretKeySpec(derivedKeyBytes, encryptionAlgorithm)

                return derivedKey
            }
        }

        return null
    }
}

@ExperimentalUnsignedTypes // just to make it clear that the experimental unsigned types are used
fun ByteArray.toHexString() = asUByteArray().joinToString("") { it.toString(16).padStart(2, '0') }

private val HEX_CHARS = "0123456789ABCDEF"
fun String.hexStringToByteArray() : ByteArray {

    val result = ByteArray(length / 2)

    for (i in 0 until length step 2) {
        val firstIndex = HEX_CHARS.indexOf(this[i]);
        val secondIndex = HEX_CHARS.indexOf(this[i + 1]);

        val octet = firstIndex.shl(4).or(secondIndex)
        result.set(i.shr(1), octet.toByte())
    }

    return result
}