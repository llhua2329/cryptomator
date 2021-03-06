/*******************************************************************************
 * Copyright (c) 2014 Sebastian Stenzel
 * This file is licensed under the terms of the MIT license.
 * See the LICENSE.txt file for more info.
 * 
 * Contributors:
 *     Sebastian Stenzel - initial API and implementation
 ******************************************************************************/
package org.cryptomator.crypto.aes256;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import javax.crypto.AEADBadTagException;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.Mac;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.ShortBufferException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.security.auth.DestroyFailedException;
import javax.security.auth.Destroyable;

import org.bouncycastle.crypto.generators.SCrypt;
import org.cryptomator.crypto.Cryptor;
import org.cryptomator.crypto.exceptions.DecryptFailedException;
import org.cryptomator.crypto.exceptions.EncryptFailedException;
import org.cryptomator.crypto.exceptions.MacAuthenticationFailedException;
import org.cryptomator.crypto.exceptions.UnsupportedKeyLengthException;
import org.cryptomator.crypto.exceptions.UnsupportedVaultException;
import org.cryptomator.crypto.exceptions.WrongPasswordException;
import org.cryptomator.siv.SivMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

public class Aes256Cryptor implements Cryptor, AesCryptographicConfiguration {

	private static final Logger LOG = LoggerFactory.getLogger(Aes256Cryptor.class);

	/**
	 * Defined in static initializer. Defaults to 256, but falls back to maximum value possible, if JCE Unlimited Strength Jurisdiction Policy Files isn't installed. Those files can be downloaded
	 * here: http://www.oracle.com/technetwork/java/javase/downloads/.
	 */
	private static final int AES_KEY_LENGTH_IN_BITS;

	/**
	 * SIV mode for deterministic filename encryption.
	 */
	private static final SivMode AES_SIV = new SivMode();

	/**
	 * PRNG for cryptographically secure random numbers. Defaults to SHA1-based number generator.
	 * 
	 * @see http://docs.oracle.com/javase/7/docs/technotes/guides/security/StandardNames.html#SecureRandom
	 */
	private final SecureRandom securePrng;

	/**
	 * Jackson JSON-Mapper.
	 */
	private final ObjectMapper objectMapper = new ObjectMapper();

	/**
	 * The decrypted master key. Its lifecycle starts with the construction of an Aes256Cryptor instance or {@link #decryptMasterKey(InputStream, CharSequence)}. Its lifecycle ends with
	 * {@link #swipeSensitiveData()}.
	 */
	private SecretKey primaryMasterKey;

	/**
	 * Decrypted secondary key used for hmac operations.
	 */
	private SecretKey hMacMasterKey;

	static {
		try {
			final int maxKeyLength = Cipher.getMaxAllowedKeyLength(AES_KEY_ALGORITHM);
			AES_KEY_LENGTH_IN_BITS = (maxKeyLength >= PREF_MASTER_KEY_LENGTH_IN_BITS) ? PREF_MASTER_KEY_LENGTH_IN_BITS : maxKeyLength;
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("Algorithm should exist.", e);
		}
	}

	/**
	 * Creates a new Cryptor with a newly initialized PRNG.
	 */
	public Aes256Cryptor() {
		byte[] bytes = new byte[AES_KEY_LENGTH_IN_BITS / Byte.SIZE];
		try {
			securePrng = SecureRandom.getInstanceStrong();
			// No setSeed needed. See SecureRandom.getInstance(String):
			// The first call to nextBytes will force the SecureRandom object to seed itself
			securePrng.nextBytes(bytes);
			this.primaryMasterKey = new SecretKeySpec(bytes, AES_KEY_ALGORITHM);
			securePrng.nextBytes(bytes);
			this.hMacMasterKey = new SecretKeySpec(bytes, HMAC_KEY_ALGORITHM);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("PRNG algorithm should exist.", e);
		} finally {
			Arrays.fill(bytes, (byte) 0);
		}
	}

	/**
	 * Encrypts the current masterKey with the given password and writes the result to the given output stream.
	 */
	@Override
	public void encryptMasterKey(OutputStream out, CharSequence password) throws IOException {
		try {
			// derive key:
			final byte[] kekSalt = randomData(SCRYPT_SALT_LENGTH);
			final SecretKey kek = scrypt(password, kekSalt, SCRYPT_COST_PARAM, SCRYPT_BLOCK_SIZE, AES_KEY_LENGTH_IN_BITS);

			// encrypt:
			final Cipher encCipher = aesKeyWrapCipher(kek, Cipher.WRAP_MODE);
			byte[] wrappedPrimaryKey = encCipher.wrap(primaryMasterKey);
			byte[] wrappedSecondaryKey = encCipher.wrap(hMacMasterKey);

			// save encrypted masterkey:
			final KeyFile keyfile = new KeyFile();
			keyfile.setVersion(KeyFile.CURRENT_VERSION);
			keyfile.setScryptSalt(kekSalt);
			keyfile.setScryptCostParam(SCRYPT_COST_PARAM);
			keyfile.setScryptBlockSize(SCRYPT_BLOCK_SIZE);
			keyfile.setKeyLength(AES_KEY_LENGTH_IN_BITS);
			keyfile.setPrimaryMasterKey(wrappedPrimaryKey);
			keyfile.setHMacMasterKey(wrappedSecondaryKey);
			objectMapper.writeValue(out, keyfile);
		} catch (InvalidKeyException | IllegalBlockSizeException ex) {
			throw new IllegalStateException("Invalid hard coded configuration.", ex);
		}
	}

	/**
	 * Reads the encrypted masterkey from the given input stream and decrypts it with the given password.
	 * 
	 * @throws DecryptFailedException If the decryption failed for various reasons (including wrong password).
	 * @throws WrongPasswordException If the provided password was wrong. Note: Sometimes the algorithm itself fails due to a wrong password. In this case a DecryptFailedException will be thrown.
	 * @throws UnsupportedKeyLengthException If the masterkey has been encrypted with a higher key length than supported by the system. In this case Java JCE needs to be installed.
	 * @throws UnsupportedVaultException If the masterkey file is too old or too modern.
	 */
	@Override
	public void decryptMasterKey(InputStream in, CharSequence password) throws DecryptFailedException, WrongPasswordException, UnsupportedKeyLengthException, IOException, UnsupportedVaultException {
		try {
			// load encrypted masterkey:
			final KeyFile keyfile = objectMapper.readValue(in, KeyFile.class);

			// check version
			if (keyfile.getVersion() != KeyFile.CURRENT_VERSION) {
				throw new UnsupportedVaultException(keyfile.getVersion(), KeyFile.CURRENT_VERSION);
			}

			// check, whether the key length is supported:
			final int maxKeyLen = Cipher.getMaxAllowedKeyLength(AES_KEY_ALGORITHM);
			if (keyfile.getKeyLength() > maxKeyLen) {
				throw new UnsupportedKeyLengthException(keyfile.getKeyLength(), maxKeyLen);
			}

			// derive key:
			final SecretKey kek = scrypt(password, keyfile.getScryptSalt(), keyfile.getScryptCostParam(), keyfile.getScryptBlockSize(), keyfile.getKeyLength());

			// decrypt and check password by catching AEAD exception
			final Cipher decCipher = aesKeyWrapCipher(kek, Cipher.UNWRAP_MODE);
			SecretKey primary = (SecretKey) decCipher.unwrap(keyfile.getPrimaryMasterKey(), AES_KEY_ALGORITHM, Cipher.SECRET_KEY);
			SecretKey secondary = (SecretKey) decCipher.unwrap(keyfile.getHMacMasterKey(), HMAC_KEY_ALGORITHM, Cipher.SECRET_KEY);

			// everything ok, assign decrypted keys:
			this.primaryMasterKey = primary;
			this.hMacMasterKey = secondary;
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("Algorithm should exist.", ex);
		} catch (InvalidKeyException e) {
			throw new WrongPasswordException();
		}
	}

	@Override
	public boolean isDestroyed() {
		return primaryMasterKey.isDestroyed() && hMacMasterKey.isDestroyed();
	}

	@Override
	public void destroy() {
		destroyQuietly(primaryMasterKey);
		destroyQuietly(hMacMasterKey);
	}

	private void destroyQuietly(Destroyable d) {
		try {
			d.destroy();
		} catch (DestroyFailedException e) {
			// ignore
		}
	}

	private Cipher aesKeyWrapCipher(SecretKey key, int cipherMode) {
		try {
			final Cipher cipher = Cipher.getInstance(AES_KEYWRAP_CIPHER);
			cipher.init(cipherMode, key);
			return cipher;
		} catch (InvalidKeyException ex) {
			throw new IllegalArgumentException("Invalid key.", ex);
		} catch (NoSuchAlgorithmException | NoSuchPaddingException ex) {
			throw new IllegalStateException("Algorithm/Padding should exist.", ex);
		}
	}

	private Cipher aesCtrCipher(SecretKey key, byte[] iv, int cipherMode) {
		try {
			final Cipher cipher = Cipher.getInstance(AES_CTR_CIPHER);
			cipher.init(cipherMode, key, new IvParameterSpec(iv));
			return cipher;
		} catch (InvalidKeyException ex) {
			throw new IllegalArgumentException("Invalid key.", ex);
		} catch (NoSuchAlgorithmException | NoSuchPaddingException | InvalidAlgorithmParameterException ex) {
			throw new IllegalStateException("Algorithm/Padding should exist and accept an IV.", ex);
		}
	}

	private Cipher aesCbcCipher(SecretKey key, byte[] iv, int cipherMode) {
		try {
			final Cipher cipher = Cipher.getInstance(AES_CBC_CIPHER);
			cipher.init(cipherMode, key, new IvParameterSpec(iv));
			return cipher;
		} catch (InvalidKeyException ex) {
			throw new IllegalArgumentException("Invalid key.", ex);
		} catch (NoSuchAlgorithmException | NoSuchPaddingException | InvalidAlgorithmParameterException ex) {
			throw new AssertionError("Every implementation of the Java platform is required to support AES/CBC/PKCS5Padding, which accepts an IV", ex);
		}
	}

	private Mac hmacSha256(SecretKey key) {
		try {
			final Mac mac = Mac.getInstance(HMAC_KEY_ALGORITHM);
			mac.init(key);
			return mac;
		} catch (NoSuchAlgorithmException e) {
			throw new AssertionError("Every implementation of the Java platform is required to support HmacSHA256.", e);
		} catch (InvalidKeyException e) {
			throw new IllegalArgumentException("Invalid key", e);
		}
	}

	private MessageDigest sha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new AssertionError("Every implementation of the Java platform is required to support Sha-256");
		}
	}

	private byte[] randomData(int length) {
		final byte[] result = new byte[length];
		securePrng.nextBytes(result);
		return result;
	}

	private SecretKey scrypt(CharSequence password, byte[] salt, int costParam, int blockSize, int keyLengthInBits) {
		// use sb, as password.toString's implementation is unknown
		final StringBuilder sb = new StringBuilder(password);
		final byte[] pw = sb.toString().getBytes();
		try {
			final byte[] key = SCrypt.generate(pw, salt, costParam, blockSize, 1, keyLengthInBits / Byte.SIZE);
			return new SecretKeySpec(key, AES_KEY_ALGORITHM);
		} finally {
			// destroy copied bytes of the plaintext password:
			Arrays.fill(pw, (byte) 0);
			for (int i = 0; i < password.length(); i++) {
				sb.setCharAt(i, (char) 0);
			}
		}
	}

	@Override
	public String encryptDirectoryPath(String cleartextDirectoryId, String nativePathSep) {
		final byte[] cleartextBytes = cleartextDirectoryId.getBytes(StandardCharsets.UTF_8);
		byte[] encryptedBytes = AES_SIV.encrypt(primaryMasterKey, hMacMasterKey, cleartextBytes);
		final byte[] hashed = sha256().digest(encryptedBytes);
		final String encryptedThenHashedPath = ENCRYPTED_FILENAME_CODEC.encodeAsString(hashed);
		return encryptedThenHashedPath.substring(0, 2) + nativePathSep + encryptedThenHashedPath.substring(2);
	}

	@Override
	public String encryptFilename(String cleartextName) {
		final byte[] cleartextBytes = cleartextName.getBytes(StandardCharsets.UTF_8);
		final byte[] encryptedBytes = AES_SIV.encrypt(primaryMasterKey, hMacMasterKey, cleartextBytes);
		return ENCRYPTED_FILENAME_CODEC.encodeAsString(encryptedBytes);
	}

	@Override
	public String decryptFilename(String ciphertextName) throws DecryptFailedException {
		final byte[] encryptedBytes = ENCRYPTED_FILENAME_CODEC.decode(ciphertextName);
		try {
			final byte[] cleartextBytes = AES_SIV.decrypt(primaryMasterKey, hMacMasterKey, encryptedBytes);
			return new String(cleartextBytes, StandardCharsets.UTF_8);
		} catch (AEADBadTagException e) {
			throw new DecryptFailedException(e);
		}
	}

	@Override
	public Long decryptedContentLength(SeekableByteChannel encryptedFile) throws IOException, MacAuthenticationFailedException {
		// read header:
		encryptedFile.position(0);
		final ByteBuffer headerBuf = ByteBuffer.allocate(104);
		final int headerBytesRead = readFromChannel(encryptedFile, headerBuf);
		if (headerBytesRead != headerBuf.capacity()) {
			return null;
		}

		// read iv:
		final byte[] iv = new byte[AES_BLOCK_LENGTH];
		headerBuf.position(0);
		headerBuf.get(iv);

		// read sensitive header data:
		final byte[] encryptedSensitiveHeaderContentBytes = new byte[48];
		headerBuf.position(24);
		headerBuf.get(encryptedSensitiveHeaderContentBytes);

		// read stored header mac:
		final byte[] storedHeaderMac = new byte[32];
		headerBuf.position(72);
		headerBuf.get(storedHeaderMac);

		// calculate mac over first 72 bytes of header:
		final Mac headerMac = this.hmacSha256(hMacMasterKey);
		headerBuf.rewind();
		headerBuf.limit(72);
		headerMac.update(headerBuf);

		final boolean macMatches = MessageDigest.isEqual(storedHeaderMac, headerMac.doFinal());
		if (!macMatches) {
			throw new MacAuthenticationFailedException("MAC authentication failed.");
		}

		// decrypt sensitive header data:
		final byte[] decryptedSensitiveHeaderContentBytes = decryptHeaderData(encryptedSensitiveHeaderContentBytes, iv);
		final ByteBuffer sensitiveHeaderContentBuf = ByteBuffer.wrap(decryptedSensitiveHeaderContentBytes);
		final Long fileSize = sensitiveHeaderContentBuf.getLong();

		return fileSize;
	}

	private byte[] decryptHeaderData(byte[] ciphertextBytes, byte[] iv) {
		try {
			final Cipher sizeCipher = aesCbcCipher(primaryMasterKey, iv, Cipher.DECRYPT_MODE);
			return sizeCipher.doFinal(ciphertextBytes);
		} catch (IllegalBlockSizeException | BadPaddingException e) {
			throw new IllegalStateException(e);
		}
	}

	private byte[] encryptHeaderData(byte[] plaintextBytes, byte[] iv) {
		try {
			final Cipher sizeCipher = aesCbcCipher(primaryMasterKey, iv, Cipher.ENCRYPT_MODE);
			return sizeCipher.doFinal(plaintextBytes);
		} catch (IllegalBlockSizeException | BadPaddingException e) {
			throw new IllegalStateException("Block size must be valid, as padding is requested. BadPaddingException not possible in encrypt mode.", e);
		}
	}

	@Override
	public Long decryptFile(SeekableByteChannel encryptedFile, OutputStream plaintextFile, boolean authenticate) throws IOException, DecryptFailedException {
		// read header:
		encryptedFile.position(0l);
		final ByteBuffer headerBuf = ByteBuffer.allocate(104);
		final int headerBytesRead = readFromChannel(encryptedFile, headerBuf);
		if (headerBytesRead != headerBuf.capacity()) {
			throw new IOException("Failed to read file header.");
		}

		// read iv:
		final byte[] iv = new byte[AES_BLOCK_LENGTH];
		headerBuf.position(0);
		headerBuf.get(iv);

		// read nonce:
		final byte[] nonce = new byte[8];
		headerBuf.position(16);
		headerBuf.get(nonce);

		// read sensitive header data:
		final byte[] encryptedSensitiveHeaderContentBytes = new byte[48];
		headerBuf.position(24);
		headerBuf.get(encryptedSensitiveHeaderContentBytes);

		// read header mac:
		final byte[] storedHeaderMac = new byte[32];
		headerBuf.position(72);
		headerBuf.get(storedHeaderMac);

		// calculate mac over first 72 bytes of header:
		if (authenticate) {
			final Mac headerMac = this.hmacSha256(hMacMasterKey);
			headerBuf.position(0);
			headerBuf.limit(72);
			headerMac.update(headerBuf);
			if (!MessageDigest.isEqual(storedHeaderMac, headerMac.doFinal())) {
				throw new MacAuthenticationFailedException("Header MAC authentication failed.");
			}
		}

		// decrypt sensitive header data:
		final byte[] fileKeyBytes = new byte[32];
		final byte[] decryptedSensitiveHeaderContentBytes = decryptHeaderData(encryptedSensitiveHeaderContentBytes, iv);
		final ByteBuffer sensitiveHeaderContentBuf = ByteBuffer.wrap(decryptedSensitiveHeaderContentBytes);
		final Long fileSize = sensitiveHeaderContentBuf.getLong();
		sensitiveHeaderContentBuf.get(fileKeyBytes);

		// prepare content decryption:
		final SecretKey fileKey = new SecretKeySpec(fileKeyBytes, AES_KEY_ALGORITHM);
		final LengthLimitingOutputStream paddingRemovingOutputStream = new LengthLimitingOutputStream(plaintextFile, fileSize);
		final CryptoWorkerExecutor executor = new CryptoWorkerExecutor(Runtime.getRuntime().availableProcessors(), (lock, blockDone, currentBlock, inputQueue) -> {
			return new DecryptWorker(lock, blockDone, currentBlock, inputQueue, authenticate, Channels.newChannel(paddingRemovingOutputStream)) {

				@Override
				protected Cipher initCipher(long startBlockNum) {
					final ByteBuffer nonceAndCounterBuf = ByteBuffer.allocate(AES_BLOCK_LENGTH);
					nonceAndCounterBuf.put(nonce);
					nonceAndCounterBuf.putLong(startBlockNum * CONTENT_MAC_BLOCK / AES_BLOCK_LENGTH);
					final byte[] nonceAndCounter = nonceAndCounterBuf.array();
					return aesCtrCipher(fileKey, nonceAndCounter, Cipher.DECRYPT_MODE);
				}

				@Override
				protected Mac initMac() {
					return hmacSha256(hMacMasterKey);
				}

				@Override
				protected void checkMac(Mac mac, long blockNum, ByteBuffer ciphertextBuf, ByteBuffer macBuf) throws MacAuthenticationFailedException {
					mac.update(iv);
					mac.update(longToByteArray(blockNum));
					mac.update(ciphertextBuf);
					final byte[] calculatedMac = mac.doFinal();
					final byte[] storedMac = new byte[mac.getMacLength()];
					macBuf.get(storedMac);
					if (!MessageDigest.isEqual(calculatedMac, storedMac)) {
						throw new MacAuthenticationFailedException("Content MAC authentication failed.");
					}
				}

				@Override
				protected void decrypt(Cipher cipher, ByteBuffer ciphertextBuf, ByteBuffer plaintextBuf) throws DecryptFailedException {
					assert plaintextBuf.remaining() >= cipher.getOutputSize(ciphertextBuf.remaining());
					try {
						cipher.update(ciphertextBuf, plaintextBuf);
					} catch (ShortBufferException e) {
						throw new DecryptFailedException(e);
					}
				}

			};
		});

		// read as many blocks from file as possible, but wait if queue is full:
		encryptedFile.position(104l);
		final int maxNumBlocks = 64;
		int numBlocks = 1;
		int bytesRead = 0;
		long blockNumber = 0;
		do {
			if (numBlocks < maxNumBlocks) {
				numBlocks++;
			}
			final int inBufSize = numBlocks * (CONTENT_MAC_BLOCK + 32);
			final ByteBuffer buf = ByteBuffer.allocate(inBufSize);
			bytesRead = readFromChannel(encryptedFile, buf);
			buf.flip();
			final int blocksRead = (int) Math.ceil(bytesRead / (double) (CONTENT_MAC_BLOCK + 32));
			final boolean consumedInTime = executor.offer(new BlocksData(buf.asReadOnlyBuffer(), blockNumber, blocksRead), 1, TimeUnit.SECONDS);
			if (!consumedInTime) {
				break;
			}
			blockNumber += numBlocks;
		} while (bytesRead == numBlocks * (CONTENT_MAC_BLOCK + 32));

		// wait for decryption workers to finish:
		try {
			executor.waitUntilDone();
		} catch (ExecutionException e) {
			final Throwable cause = e.getCause();
			if (cause instanceof IOException) {
				throw (IOException) cause;
			} else if (cause instanceof RuntimeException) {
				throw (RuntimeException) cause;
			} else {
				LOG.error("Unexpected exception", e);
			}
		} finally {
			destroyQuietly(fileKey);
		}

		return paddingRemovingOutputStream.getBytesWritten();
	}

	@Override
	public Long decryptRange(SeekableByteChannel encryptedFile, OutputStream plaintextFile, long pos, long length, boolean authenticate) throws IOException, DecryptFailedException {
		// read header:
		encryptedFile.position(0l);
		final ByteBuffer headerBuf = ByteBuffer.allocate(104);
		final int headerBytesRead = readFromChannel(encryptedFile, headerBuf);
		if (headerBytesRead != headerBuf.capacity()) {
			throw new IOException("Failed to read file header.");
		}

		// read iv:
		final byte[] iv = new byte[AES_BLOCK_LENGTH];
		headerBuf.position(0);
		headerBuf.get(iv);

		// read nonce:
		final byte[] nonce = new byte[8];
		headerBuf.position(16);
		headerBuf.get(nonce);

		// read sensitive header data:
		final byte[] encryptedSensitiveHeaderContentBytes = new byte[48];
		headerBuf.position(24);
		headerBuf.get(encryptedSensitiveHeaderContentBytes);

		// read header mac:
		final byte[] storedHeaderMac = new byte[32];
		headerBuf.position(72);
		headerBuf.get(storedHeaderMac);

		// calculate mac over first 72 bytes of header:
		if (authenticate) {
			final Mac headerMac = this.hmacSha256(hMacMasterKey);
			headerBuf.position(0);
			headerBuf.limit(72);
			headerMac.update(headerBuf);
			if (!MessageDigest.isEqual(storedHeaderMac, headerMac.doFinal())) {
				throw new MacAuthenticationFailedException("Header MAC authentication failed.");
			}
		}

		// decrypt sensitive header data:
		final byte[] fileKeyBytes = new byte[32];
		final byte[] decryptedSensitiveHeaderContentBytes = decryptHeaderData(encryptedSensitiveHeaderContentBytes, iv);
		final ByteBuffer sensitiveHeaderContentBuf = ByteBuffer.wrap(decryptedSensitiveHeaderContentBytes);
		final Long fileSize = sensitiveHeaderContentBuf.getLong();
		sensitiveHeaderContentBuf.get(fileKeyBytes);

		assert pos + length - 1 < fileSize;

		// find first relevant block:
		final long startBlock = pos / CONTENT_MAC_BLOCK; // floor
		final long startByte = startBlock * (CONTENT_MAC_BLOCK + 32) + 104;
		final long offsetFromFirstBlock = pos - startBlock * CONTENT_MAC_BLOCK;

		// append correct counter value to nonce:
		final ByteBuffer nonceAndCounterBuf = ByteBuffer.allocate(AES_BLOCK_LENGTH);
		nonceAndCounterBuf.put(nonce);
		nonceAndCounterBuf.putLong(startBlock * CONTENT_MAC_BLOCK / AES_BLOCK_LENGTH);
		final byte[] nonceAndCounter = nonceAndCounterBuf.array();

		// content decryption:
		encryptedFile.position(startByte);
		final SecretKey fileKey = new SecretKeySpec(fileKeyBytes, AES_KEY_ALGORITHM);
		final Cipher cipher = this.aesCtrCipher(fileKey, nonceAndCounter, Cipher.DECRYPT_MODE);
		final Mac contentMac = this.hmacSha256(hMacMasterKey);

		try {
			// reading ciphered input and MACs interleaved:
			long bytesWritten = 0;
			final ByteBuffer buf = ByteBuffer.allocate(CONTENT_MAC_BLOCK + 32);
			int n = 0;
			long blockNum = startBlock;
			while ((n = readFromChannel(encryptedFile, buf)) > 0 && bytesWritten < length) {
				if (n < 32) {
					throw new DecryptFailedException("Invalid file content, missing MAC.");
				}

				buf.flip();
				final ByteBuffer ciphertextBuf = buf.asReadOnlyBuffer();
				ciphertextBuf.limit(n - 32);

				// check MAC of current block:
				if (authenticate) {
					final byte[] storedMac = new byte[contentMac.getMacLength()];
					final ByteBuffer storedMacBuf = buf.asReadOnlyBuffer();
					storedMacBuf.position(n - 32);
					storedMacBuf.get(storedMac);
					contentMac.update(iv);
					contentMac.update(longToByteArray(blockNum));
					contentMac.update(ciphertextBuf);
					ciphertextBuf.rewind();
					final byte[] calculatedMac = contentMac.doFinal();
					if (!MessageDigest.isEqual(calculatedMac, storedMac)) {
						throw new MacAuthenticationFailedException("Content MAC authentication failed.");
					}
				}

				// decrypt block:
				final ByteBuffer plaintextBuf = ByteBuffer.allocate(cipher.getOutputSize(ciphertextBuf.remaining()));
				cipher.update(ciphertextBuf, plaintextBuf);
				plaintextBuf.flip();
				final int offset = (bytesWritten == 0) ? (int) offsetFromFirstBlock : 0;
				final long pending = length - bytesWritten;
				final int available = plaintextBuf.remaining() - offset;
				final int currentBatch = (int) Math.min(pending, available);

				plaintextFile.write(plaintextBuf.array(), offset, currentBatch);
				bytesWritten += currentBatch;
				blockNum++;
				buf.rewind();
			}
			return bytesWritten;
		} catch (ShortBufferException e) {
			throw new IllegalStateException("Output buffer size known to fit.", e);
		} finally {
			destroyQuietly(fileKey);
		}
	}

	/**
	 * header = {16 byte iv, 8 byte nonce, 48 byte sensitive header data (file size + file key + padding), 32 byte headerMac}
	 */
	@Override
	public Long encryptFile(InputStream plaintextFile, SeekableByteChannel encryptedFile) throws IOException, EncryptFailedException {
		// truncate file
		encryptedFile.truncate(0l);

		// choose a random header IV:
		final byte[] iv = randomData(AES_BLOCK_LENGTH);

		// chosse 8 byte random nonce and 8 byte counter set to zero:
		final byte[] nonce = randomData(8);

		// choose a random content key:
		final byte[] fileKeyBytes = randomData(32);

		// 104 byte header buffer (16 header IV, 8 content nonce, 48 sensitive header data, 32 headerMac), filled after writing the content
		final ByteBuffer headerBuf = ByteBuffer.allocate(104);
		headerBuf.limit(104);
		encryptedFile.write(headerBuf);

		// prepare content encryption:
		final SecretKey fileKey = new SecretKeySpec(fileKeyBytes, AES_KEY_ALGORITHM);
		final CryptoWorkerExecutor executor = new CryptoWorkerExecutor(Runtime.getRuntime().availableProcessors(), (lock, blockDone, currentBlock, inputQueue) -> {
			return new EncryptWorker(lock, blockDone, currentBlock, inputQueue, encryptedFile) {

				@Override
				protected Cipher initCipher(long startBlockNum) {
					final ByteBuffer nonceAndCounterBuf = ByteBuffer.allocate(AES_BLOCK_LENGTH);
					nonceAndCounterBuf.put(nonce);
					nonceAndCounterBuf.putLong(startBlockNum * CONTENT_MAC_BLOCK / AES_BLOCK_LENGTH);
					final byte[] nonceAndCounter = nonceAndCounterBuf.array();
					return aesCtrCipher(fileKey, nonceAndCounter, Cipher.ENCRYPT_MODE);
				}

				@Override
				protected Mac initMac() {
					return hmacSha256(hMacMasterKey);
				}

				@Override
				protected byte[] calcMac(Mac mac, long blockNum, ByteBuffer ciphertextBuf) {
					mac.update(iv);
					mac.update(longToByteArray(blockNum));
					mac.update(ciphertextBuf);
					return mac.doFinal();
				}

				@Override
				protected void encrypt(Cipher cipher, ByteBuffer plaintextBuf, ByteBuffer ciphertextBuf) throws EncryptFailedException {
					try {
						assert ciphertextBuf.remaining() >= cipher.getOutputSize(plaintextBuf.remaining());
						cipher.update(plaintextBuf, ciphertextBuf);
					} catch (ShortBufferException e) {
						throw new EncryptFailedException(e);
					}
				}
			};
		});

		// read as many blocks from file as possible, but wait if queue is full:
		final byte[] randomPadding = this.randomData(AES_BLOCK_LENGTH);
		final LengthObfuscatingInputStream in = new LengthObfuscatingInputStream(plaintextFile, randomPadding);
		final ReadableByteChannel channel = Channels.newChannel(in);
		int bytesRead = 0;
		long blockNumber = 0;
		final int maxNumBlocks = 64;
		int numBlocks = 0;
		do {
			if (numBlocks < maxNumBlocks) {
				numBlocks++;
			}
			final int inBufSize = numBlocks * CONTENT_MAC_BLOCK;
			final ByteBuffer inBuf = ByteBuffer.allocate(inBufSize);
			bytesRead = readFromChannel(channel, inBuf);
			inBuf.flip();
			final int blocksRead = (int) Math.ceil(bytesRead / (double) CONTENT_MAC_BLOCK);
			final boolean consumedInTime = executor.offer(new BlocksData(inBuf.asReadOnlyBuffer(), blockNumber, blocksRead), 1, TimeUnit.SECONDS);
			if (!consumedInTime) {
				break;
			}
			blockNumber += numBlocks;
		} while (bytesRead == numBlocks * CONTENT_MAC_BLOCK);

		// wait for encryption workers to finish:
		try {
			executor.waitUntilDone();
		} catch (ExecutionException e) {
			final Throwable cause = e.getCause();
			if (cause instanceof IOException) {
				throw (IOException) cause;
			} else if (cause instanceof RuntimeException) {
				throw (RuntimeException) cause;
			} else {
				LOG.error("Unexpected exception", e);
			}
		} finally {
			destroyQuietly(fileKey);
		}

		// create and write header:
		final long plaintextSize = in.getRealInputLength();
		final ByteBuffer sensitiveHeaderContentBuf = ByteBuffer.allocate(Long.BYTES + fileKeyBytes.length);
		sensitiveHeaderContentBuf.putLong(plaintextSize);
		sensitiveHeaderContentBuf.put(fileKeyBytes);
		headerBuf.clear();
		headerBuf.put(iv);
		headerBuf.put(nonce);
		headerBuf.put(encryptHeaderData(sensitiveHeaderContentBuf.array(), iv));
		headerBuf.flip();
		final Mac headerMac = this.hmacSha256(hMacMasterKey);
		headerMac.update(headerBuf);
		headerBuf.limit(104);
		headerBuf.put(headerMac.doFinal());
		headerBuf.flip();
		encryptedFile.position(0);
		encryptedFile.write(headerBuf);

		return plaintextSize;
	}

	private byte[] longToByteArray(long lng) {
		return ByteBuffer.allocate(Long.SIZE / Byte.SIZE).putLong(lng).array();
	}

	/**
	 * Reads bytes from a ReadableByteChannel.
	 * <p>
	 * This implementation guarantees that it will read as many bytes
	 * as possible before giving up; this may not always be the case for
	 * subclasses of {@link ReadableByteChannel}.
	 *
	 * @param input the byte channel to read
	 * @param buffer byte buffer destination
	 * @return the actual length read; may be less than requested if EOF was reached
	 * @throws IOException if a read error occurs
	 * @see
	 * 		<a href="http://commons.apache.org/proper/commons-io/apidocs/src-html/org/apache/commons/io/IOUtils.html">Apache Commons IOUtils 2.5</a>
	 */
	public static int readFromChannel(final ReadableByteChannel input, final ByteBuffer buffer) throws IOException {
		final int length = buffer.remaining();
		while (buffer.remaining() > 0) {
			final int count = input.read(buffer);
			if (count == -1) { // EOF
				break;
			}
		}
		return length - buffer.remaining();
	}

}
