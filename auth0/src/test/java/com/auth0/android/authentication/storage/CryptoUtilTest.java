package com.auth0.android.authentication.storage;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.security.KeyPairGeneratorSpec;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.support.annotation.RequiresApi;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.math.BigInteger;
import java.security.Key;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.util.Date;
import java.util.concurrent.TimeUnit;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.security.auth.x500.X500Principal;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.Assert.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.powermock.api.mockito.PowerMockito.doReturn;
import static org.powermock.api.mockito.PowerMockito.doThrow;
import static org.powermock.api.mockito.PowerMockito.mock;

/**
 * Created by lbalmaceda on 8/24/17.
 */
@RequiresApi(api = Build.VERSION_CODES.KITKAT)
@RunWith(PowerMockRunner.class)
@PrepareForTest({CryptoUtil.class, KeyGenerator.class, TextUtils.class, Build.VERSION.class, Base64.class, Cipher.class, Log.class})
@Config(constants = com.auth0.android.auth0.BuildConfig.class, sdk = 22, manifest = Config.NONE)
public class CryptoUtilTest {

    private static final String RSA_TRANSFORMATION = "RSA/ECB/PKCS1Padding";
    private static final String AES_TRANSFORMATION = "AES/GCM/NOPADDING";
    private static final String CERTIFICATE_PRINCIPAL = "CN=Auth0.Android,O=Auth0";
    private static final String ANDROID_KEY_STORE = "AndroidKeyStore";
    private static final String ALGORITHM_AES = "AES";
    private static final String ALGORITHM_RSA = "RSA";


    @Rule
    public ExpectedException exception = ExpectedException.none();

    private Storage storage = PowerMockito.mock(Storage.class);
    private Cipher rsaCipher = PowerMockito.mock(Cipher.class);
    private Cipher aesCipher = PowerMockito.mock(Cipher.class);
    private KeyStore keyStore = PowerMockito.mock(KeyStore.class);
    private KeyPairGenerator keyPairGenerator = PowerMockito.mock(KeyPairGenerator.class);
    private KeyGenerator keyGenerator = PowerMockito.mock(KeyGenerator.class);

    private CryptoUtil cryptoUtil;

    private static final String KEY_ALIAS = "keyName";
    private Context context;

    //Android KeyStore not accessible using Robolectric
    //Must test using white-box approach
    //Ref: https://github.com/robolectric/robolectric/issues/1518
    @Before
    public void setUp() throws Exception {
        PowerMockito.mockStatic(Log.class);
        PowerMockito.mockStatic(TextUtils.class);
        PowerMockito.when(TextUtils.isEmpty(anyString())).then(new Answer<Boolean>() {
            @Override
            public Boolean answer(InvocationOnMock invocation) throws Throwable {
                String input = invocation.getArgumentAt(0, String.class);
                return input == null || input.isEmpty();
            }
        });

        context = mock(Context.class);
        cryptoUtil = newCryptoUtilSpy();
    }

    @Test
    public void shouldThrowWhenRSAKeyAliasIsInvalid() throws Exception {
        exception.expect(IllegalArgumentException.class);
        exception.expectMessage("RSA and AES Key alias must be valid.");
        new CryptoUtil(RuntimeEnvironment.application, storage, " ");
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    @Test
    @Config(constants = com.auth0.android.auth0.BuildConfig.class, sdk = 20, manifest = Config.NONE)
    public void shouldNotCreateProtectedRSAKeyPairIfMissingAndLockScreenEnabledOnAPI20() throws Exception {
        ReflectionHelpers.setStaticField(Build.VERSION.class, "SDK_INT", 20);

        PowerMockito.when(keyStore.containsAlias(KEY_ALIAS)).thenReturn(false);
        KeyStore.PrivateKeyEntry expectedEntry = PowerMockito.mock(KeyStore.PrivateKeyEntry.class);
        PowerMockito.when(keyStore.getEntry(KEY_ALIAS, null)).thenReturn(expectedEntry);

        KeyPairGeneratorSpec spec = PowerMockito.mock(KeyPairGeneratorSpec.class);
        KeyPairGeneratorSpec.Builder builder = newKeyPairGeneratorSpecBuilder(spec);
        PowerMockito.whenNew(KeyPairGeneratorSpec.Builder.class).withAnyArguments().thenReturn(builder);

        ArgumentCaptor<X500Principal> principalCaptor = ArgumentCaptor.forClass(X500Principal.class);
        ArgumentCaptor<Date> startDateCaptor = ArgumentCaptor.forClass(Date.class);
        ArgumentCaptor<Date> endDateCaptor = ArgumentCaptor.forClass(Date.class);

        //Set LockScreen as Disabled
        KeyguardManager kService = PowerMockito.mock(KeyguardManager.class);
        PowerMockito.when(context.getSystemService(Context.KEYGUARD_SERVICE)).thenReturn(kService);
        PowerMockito.when(kService.isKeyguardSecure()).thenReturn(true);

        final KeyStore.PrivateKeyEntry entry = cryptoUtil.getRSAKeyEntry();

        Mockito.verify(builder).setKeySize(2048);
        Mockito.verify(builder).setSubject(principalCaptor.capture());
        Mockito.verify(builder).setAlias(KEY_ALIAS);
        Mockito.verify(builder).setSerialNumber(BigInteger.ONE);
        Mockito.verify(builder).setStartDate(startDateCaptor.capture());
        Mockito.verify(builder).setEndDate(endDateCaptor.capture());
        Mockito.verify(builder, Mockito.never()).setEncryptionRequired();
        Mockito.verify(keyPairGenerator).initialize(spec);
        Mockito.verify(keyPairGenerator).generateKeyPair();

        assertThat(principalCaptor.getValue(), is(notNullValue()));
        assertThat(principalCaptor.getValue().getName(), is(CERTIFICATE_PRINCIPAL));

        assertThat(startDateCaptor.getValue(), is(notNullValue()));
        long diffMillis = startDateCaptor.getValue().getTime() - new Date().getTime();
        long days = TimeUnit.MILLISECONDS.toDays(diffMillis);
        assertThat(days, is(0L)); //Date is Today

        assertThat(endDateCaptor.getValue(), is(notNullValue()));
        diffMillis = endDateCaptor.getValue().getTime() - new Date().getTime();
        days = TimeUnit.MILLISECONDS.toDays(diffMillis);
        assertThat(days, is(greaterThan(25 * 365L))); //Date more than 25 Years in days

        assertThat(entry, is(expectedEntry));
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    @Test
    @Config(constants = com.auth0.android.auth0.BuildConfig.class, sdk = 21, manifest = Config.NONE)
    public void shouldCreateUnprotectedRSAKeyPairIfMissingAndLockScreenDisabledOnAPI21() throws Exception {
        ReflectionHelpers.setStaticField(Build.VERSION.class, "SDK_INT", 21);

        PowerMockito.when(keyStore.containsAlias(KEY_ALIAS)).thenReturn(false);
        KeyStore.PrivateKeyEntry expectedEntry = PowerMockito.mock(KeyStore.PrivateKeyEntry.class);
        PowerMockito.when(keyStore.getEntry(KEY_ALIAS, null)).thenReturn(expectedEntry);

        KeyPairGeneratorSpec spec = PowerMockito.mock(KeyPairGeneratorSpec.class);
        KeyPairGeneratorSpec.Builder builder = newKeyPairGeneratorSpecBuilder(spec);
        PowerMockito.whenNew(KeyPairGeneratorSpec.Builder.class).withAnyArguments().thenReturn(builder);

        ArgumentCaptor<X500Principal> principalCaptor = ArgumentCaptor.forClass(X500Principal.class);
        ArgumentCaptor<Date> startDateCaptor = ArgumentCaptor.forClass(Date.class);
        ArgumentCaptor<Date> endDateCaptor = ArgumentCaptor.forClass(Date.class);

        //Set LockScreen as Disabled
        KeyguardManager kService = PowerMockito.mock(KeyguardManager.class);
        PowerMockito.when(context.getSystemService(Context.KEYGUARD_SERVICE)).thenReturn(kService);
        PowerMockito.when(kService.isKeyguardSecure()).thenReturn(false);
        PowerMockito.when(kService.createConfirmDeviceCredentialIntent(any(CharSequence.class), any(CharSequence.class))).thenReturn(null);

        final KeyStore.PrivateKeyEntry entry = cryptoUtil.getRSAKeyEntry();

        Mockito.verify(builder).setKeySize(2048);
        Mockito.verify(builder).setSubject(principalCaptor.capture());
        Mockito.verify(builder).setAlias(KEY_ALIAS);
        Mockito.verify(builder).setSerialNumber(BigInteger.ONE);
        Mockito.verify(builder).setStartDate(startDateCaptor.capture());
        Mockito.verify(builder).setEndDate(endDateCaptor.capture());
        Mockito.verify(builder, Mockito.never()).setEncryptionRequired();
        Mockito.verify(keyPairGenerator).initialize(spec);
        Mockito.verify(keyPairGenerator).generateKeyPair();

        assertThat(principalCaptor.getValue(), is(notNullValue()));
        assertThat(principalCaptor.getValue().getName(), is(CERTIFICATE_PRINCIPAL));

        assertThat(startDateCaptor.getValue(), is(notNullValue()));
        long diffMillis = startDateCaptor.getValue().getTime() - new Date().getTime();
        long days = TimeUnit.MILLISECONDS.toDays(diffMillis);
        assertThat(days, is(0L)); //Date is Today

        assertThat(endDateCaptor.getValue(), is(notNullValue()));
        diffMillis = endDateCaptor.getValue().getTime() - new Date().getTime();
        days = TimeUnit.MILLISECONDS.toDays(diffMillis);
        assertThat(days, is(greaterThan(25 * 365L))); //Date more than 25 Years in days

        assertThat(entry, is(expectedEntry));
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    @Test
    @Config(constants = com.auth0.android.auth0.BuildConfig.class, sdk = 21, manifest = Config.NONE)
    public void shouldCreateProtectedRSAKeyPairIfMissingAndLockScreenEnabledOnAPI21() throws Exception {
        ReflectionHelpers.setStaticField(Build.VERSION.class, "SDK_INT", 21);

        PowerMockito.when(keyStore.containsAlias(KEY_ALIAS)).thenReturn(false);
        KeyStore.PrivateKeyEntry expectedEntry = PowerMockito.mock(KeyStore.PrivateKeyEntry.class);
        PowerMockito.when(keyStore.getEntry(KEY_ALIAS, null)).thenReturn(expectedEntry);

        KeyPairGeneratorSpec spec = PowerMockito.mock(KeyPairGeneratorSpec.class);
        KeyPairGeneratorSpec.Builder builder = newKeyPairGeneratorSpecBuilder(spec);
        PowerMockito.whenNew(KeyPairGeneratorSpec.Builder.class).withAnyArguments().thenReturn(builder);

        ArgumentCaptor<X500Principal> principalCaptor = ArgumentCaptor.forClass(X500Principal.class);
        ArgumentCaptor<Date> startDateCaptor = ArgumentCaptor.forClass(Date.class);
        ArgumentCaptor<Date> endDateCaptor = ArgumentCaptor.forClass(Date.class);

        //Set LockScreen as Enabled
        KeyguardManager kService = PowerMockito.mock(KeyguardManager.class);
        PowerMockito.when(context.getSystemService(Context.KEYGUARD_SERVICE)).thenReturn(kService);
        PowerMockito.when(kService.isKeyguardSecure()).thenReturn(true);
        PowerMockito.when(kService.createConfirmDeviceCredentialIntent(any(CharSequence.class), any(CharSequence.class))).thenReturn(new Intent());

        final KeyStore.PrivateKeyEntry entry = cryptoUtil.getRSAKeyEntry();

        Mockito.verify(builder).setKeySize(2048);
        Mockito.verify(builder).setSubject(principalCaptor.capture());
        Mockito.verify(builder).setAlias(KEY_ALIAS);
        Mockito.verify(builder).setSerialNumber(BigInteger.ONE);
        Mockito.verify(builder).setStartDate(startDateCaptor.capture());
        Mockito.verify(builder).setEndDate(endDateCaptor.capture());
        Mockito.verify(builder).setEncryptionRequired();
        Mockito.verify(keyPairGenerator).initialize(spec);
        Mockito.verify(keyPairGenerator).generateKeyPair();

        assertThat(principalCaptor.getValue(), is(notNullValue()));
        assertThat(principalCaptor.getValue().getName(), is(CERTIFICATE_PRINCIPAL));

        assertThat(startDateCaptor.getValue(), is(notNullValue()));
        long diffMillis = startDateCaptor.getValue().getTime() - new Date().getTime();
        long days = TimeUnit.MILLISECONDS.toDays(diffMillis);
        assertThat(days, is(0L)); //Date is Today

        assertThat(endDateCaptor.getValue(), is(notNullValue()));
        diffMillis = endDateCaptor.getValue().getTime() - new Date().getTime();
        days = TimeUnit.MILLISECONDS.toDays(diffMillis);
        assertThat(days, is(greaterThan(25 * 365L))); //Date more than 25 Years in days

        assertThat(entry, is(expectedEntry));
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    @Test
    @Config(constants = com.auth0.android.auth0.BuildConfig.class, sdk = 23, manifest = Config.NONE)
    public void shouldCreateRSAKeyPairIfMissingOnAPI23AndUp() throws Exception {
        ReflectionHelpers.setStaticField(Build.VERSION.class, "SDK_INT", 23);

        PowerMockito.when(keyStore.containsAlias(KEY_ALIAS)).thenReturn(false);
        KeyStore.PrivateKeyEntry expectedEntry = PowerMockito.mock(KeyStore.PrivateKeyEntry.class);
        PowerMockito.when(keyStore.getEntry(KEY_ALIAS, null)).thenReturn(expectedEntry);

        KeyGenParameterSpec spec = PowerMockito.mock(KeyGenParameterSpec.class);
        KeyGenParameterSpec.Builder builder = newKeyGenParameterSpecBuilder(spec);
        PowerMockito.whenNew(KeyGenParameterSpec.Builder.class).withArguments(KEY_ALIAS, KeyProperties.PURPOSE_DECRYPT | KeyProperties.PURPOSE_ENCRYPT).thenReturn(builder);

        ArgumentCaptor<X500Principal> principalCaptor = ArgumentCaptor.forClass(X500Principal.class);
        ArgumentCaptor<Date> startDateCaptor = ArgumentCaptor.forClass(Date.class);
        ArgumentCaptor<Date> endDateCaptor = ArgumentCaptor.forClass(Date.class);


        final KeyStore.PrivateKeyEntry entry = cryptoUtil.getRSAKeyEntry();

        Mockito.verify(builder).setKeySize(2048);
        Mockito.verify(builder).setCertificateSubject(principalCaptor.capture());
        Mockito.verify(builder).setCertificateSerialNumber(BigInteger.ONE);
        Mockito.verify(builder).setCertificateNotBefore(startDateCaptor.capture());
        Mockito.verify(builder).setCertificateNotAfter(endDateCaptor.capture());
        Mockito.verify(builder).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1);
        Mockito.verify(builder).setBlockModes(KeyProperties.BLOCK_MODE_ECB);
        Mockito.verify(keyPairGenerator).initialize(spec);
        Mockito.verify(keyPairGenerator).generateKeyPair();

        assertThat(principalCaptor.getValue(), is(notNullValue()));
        assertThat(principalCaptor.getValue().getName(), is(CERTIFICATE_PRINCIPAL));

        assertThat(startDateCaptor.getValue(), is(notNullValue()));
        long diffMillis = startDateCaptor.getValue().getTime() - new Date().getTime();
        long days = TimeUnit.MILLISECONDS.toDays(diffMillis);
        assertThat(days, is(0L)); //Date is Today

        assertThat(endDateCaptor.getValue(), is(notNullValue()));
        diffMillis = endDateCaptor.getValue().getTime() - new Date().getTime();
        days = TimeUnit.MILLISECONDS.toDays(diffMillis);
        assertThat(days, is(greaterThan(25 * 365L))); //Date more than 25 Years in days

        assertThat(entry, is(expectedEntry));
    }

    @Test
    public void shouldUseExistingRSAKeyPair() throws Exception {
        KeyStore.PrivateKeyEntry entry = PowerMockito.mock(KeyStore.PrivateKeyEntry.class);
        PowerMockito.when(keyStore.containsAlias(KEY_ALIAS)).thenReturn(true);
        PowerMockito.when(keyStore.getEntry(KEY_ALIAS, null)).thenReturn(entry);

        KeyStore.PrivateKeyEntry rsaEntry = cryptoUtil.getRSAKeyEntry();
        assertThat(rsaEntry, is(notNullValue()));
        assertThat(rsaEntry, is(entry));
    }

    @Test
    public void shouldCreateAESKeyIfMissing() throws Exception {
        byte[] sampleBytes = new byte[]{0, 1, 2, 3, 4, 5};
        PowerMockito.mockStatic(Base64.class);
        PowerMockito.when(Base64.encode(sampleBytes, Base64.DEFAULT)).thenReturn("data".getBytes());
        PowerMockito.when(storage.retrieveString(KEY_ALIAS)).thenReturn(null);
        doReturn(sampleBytes).when(cryptoUtil).RSAEncrypt(sampleBytes);

        Key secretKey = PowerMockito.mock(SecretKey.class);
        PowerMockito.when(secretKey.getEncoded()).thenReturn(sampleBytes);
        PowerMockito.when(keyGenerator.generateKey()).thenReturn((SecretKey) secretKey);


        final byte[] aesKey = cryptoUtil.getAESKey();

        Mockito.verify(keyGenerator).init(256);
        Mockito.verify(keyGenerator).generateKey();
        Mockito.verify(storage).store(KEY_ALIAS, "data");

        assertThat(aesKey, is(notNullValue()));
        assertThat(aesKey, is(sampleBytes));
    }

    @Test
    public void shouldUseExistingAESKey() throws Exception {
        byte[] sampleBytes = new byte[]{0, 1, 2, 3, 4, 5};
        PowerMockito.mockStatic(Base64.class);
        PowerMockito.when(Base64.decode("data", Base64.DEFAULT)).thenReturn(sampleBytes);
        PowerMockito.when(storage.retrieveString(KEY_ALIAS)).thenReturn("data");
        doReturn(sampleBytes).when(cryptoUtil).RSADecrypt(sampleBytes);

        final byte[] aesKey = cryptoUtil.getAESKey();
        assertThat(aesKey, is(notNullValue()));
        assertThat(aesKey, is(sampleBytes));
    }

    @Test
    public void shouldRSAEncryptData() throws Exception {
        byte[] sampleInput = new byte[]{0, 1, 2, 3, 4, 5};
        byte[] sampleOutput = new byte[]{99, 33, 11};

        Certificate certificate = PowerMockito.mock(Certificate.class);
        KeyStore.PrivateKeyEntry privateKeyEntry = PowerMockito.mock(KeyStore.PrivateKeyEntry.class);
        doReturn(certificate).when(privateKeyEntry).getCertificate();
        doReturn(privateKeyEntry).when(cryptoUtil).getRSAKeyEntry();
        doReturn(sampleOutput).when(rsaCipher).doFinal(sampleInput);

        final byte[] output = cryptoUtil.RSAEncrypt(sampleInput);

        Mockito.verify(rsaCipher).init(Cipher.ENCRYPT_MODE, certificate);
        assertThat(output, is(sampleOutput));
    }

    @Test
    public void shouldRSADecryptData() throws Exception {
        byte[] sampleInput = new byte[]{0, 1, 2, 3, 4, 5};
        byte[] sampleOutput = new byte[]{99, 33, 11};

        Certificate certificate = PowerMockito.mock(Certificate.class);
        KeyStore.PrivateKeyEntry privateKeyEntry = PowerMockito.mock(KeyStore.PrivateKeyEntry.class);
        doReturn(certificate).when(privateKeyEntry).getCertificate();
        doReturn(privateKeyEntry).when(cryptoUtil).getRSAKeyEntry();
        doReturn(sampleOutput).when(rsaCipher).doFinal(sampleInput);

        final byte[] output = cryptoUtil.RSAEncrypt(sampleInput);

        Mockito.verify(rsaCipher).init(Cipher.ENCRYPT_MODE, certificate);
        assertThat(output, is(sampleOutput));
    }

    @Test
    public void shouldDeleteKeysOnErrorWhenTryingToRSADecrypt() throws Exception {
        PrivateKey privateKey = PowerMockito.mock(PrivateKey.class);
        KeyStore.PrivateKeyEntry privateKeyEntry = PowerMockito.mock(KeyStore.PrivateKeyEntry.class);
        doReturn(privateKey).when(privateKeyEntry).getPrivateKey();
        doReturn(privateKeyEntry).when(cryptoUtil).getRSAKeyEntry();

        doThrow(new BadPaddingException()).when(rsaCipher).doFinal(any(byte[].class));
        cryptoUtil.RSADecrypt(new byte[0]);
        doThrow(new IllegalBlockSizeException()).when(rsaCipher).doFinal(any(byte[].class));
        cryptoUtil.RSADecrypt(new byte[0]);

        Mockito.verify(keyStore, Mockito.times(2)).load(null);
        Mockito.verify(keyStore, Mockito.times(2)).deleteEntry(KEY_ALIAS);
        Mockito.verify(storage, Mockito.times(2)).remove(KEY_ALIAS);
        Mockito.verify(storage, Mockito.times(2)).remove(KEY_ALIAS + "_iv");
    }

    @Test
    public void shouldDeleteKeysOnErrorWhenTryingToRSAEncrypt() throws Exception {
        Certificate certificate = PowerMockito.mock(Certificate.class);
        KeyStore.PrivateKeyEntry privateKeyEntry = PowerMockito.mock(KeyStore.PrivateKeyEntry.class);
        doReturn(certificate).when(privateKeyEntry).getCertificate();
        doReturn(privateKeyEntry).when(cryptoUtil).getRSAKeyEntry();

        doThrow(new BadPaddingException()).when(rsaCipher).doFinal(any(byte[].class));
        cryptoUtil.RSAEncrypt(new byte[0]);
        doThrow(new IllegalBlockSizeException()).when(rsaCipher).doFinal(any(byte[].class));
        cryptoUtil.RSAEncrypt(new byte[0]);

        Mockito.verify(keyStore, Mockito.times(2)).load(null);
        Mockito.verify(keyStore, Mockito.times(2)).deleteEntry(KEY_ALIAS);
        Mockito.verify(storage, Mockito.times(2)).remove(KEY_ALIAS);
        Mockito.verify(storage, Mockito.times(2)).remove(KEY_ALIAS + "_iv");
    }

    @Test
    public void shouldAESEncryptData() throws Exception {
        ArgumentCaptor<SecretKey> secretKeyCaptor = ArgumentCaptor.forClass(SecretKey.class);
        byte[] aesKey = "aes-decrypted-key".getBytes();
        byte[] data = "data".getBytes();
        byte[] encryptedData = new byte[]{0, 1, 2, 3, 4, 5};
        byte[] iv = new byte[]{99, 99, 11, 11};
        byte[] encodedIv = "iv-data".getBytes();

        doReturn(aesKey).when(cryptoUtil).getAESKey();
        doReturn(encryptedData).when(aesCipher).doFinal(data);
        PowerMockito.when(aesCipher.doFinal(data)).thenReturn(encryptedData);
        PowerMockito.when(aesCipher.getIV()).thenReturn(iv);
        PowerMockito.mockStatic(Base64.class);
        PowerMockito.when(Base64.encode(iv, Base64.DEFAULT)).thenReturn(encodedIv);

        final byte[] encrypted = cryptoUtil.encrypt(data);


        Mockito.verify(aesCipher).init(eq(Cipher.ENCRYPT_MODE), secretKeyCaptor.capture());
        assertThat(secretKeyCaptor.getValue(), is(notNullValue()));
        assertThat(secretKeyCaptor.getValue().getAlgorithm(), is(ALGORITHM_AES));
        assertThat(secretKeyCaptor.getValue().getEncoded(), is(aesKey));

        Mockito.verify(storage).store(KEY_ALIAS + "_iv", "iv-data");
        assertThat(encrypted, is(encryptedData));
    }

    @Test
    public void shouldAESDecryptData() throws Exception {
        ArgumentCaptor<SecretKey> secretKeyCaptor = ArgumentCaptor.forClass(SecretKey.class);
        ArgumentCaptor<IvParameterSpec> ivParameterSpecCaptor = ArgumentCaptor.forClass(IvParameterSpec.class);
        byte[] aesKey = "aes-decrypted-key".getBytes();
        byte[] data = "data".getBytes();
        byte[] decryptedData = new byte[]{0, 1, 2, 3, 4, 5};
        String encodedIv = "iv-data";

        doReturn(aesKey).when(cryptoUtil).getAESKey();
        doReturn(decryptedData).when(aesCipher).doFinal(data);
        PowerMockito.when(aesCipher.doFinal(data)).thenReturn(decryptedData);
        PowerMockito.when(storage.retrieveString(KEY_ALIAS + "_iv")).thenReturn(encodedIv);
        PowerMockito.mockStatic(Base64.class);
        PowerMockito.when(Base64.decode(encodedIv, Base64.DEFAULT)).thenReturn(encodedIv.getBytes());

        final byte[] decrypted = cryptoUtil.decrypt(data);


        Mockito.verify(aesCipher).init(eq(Cipher.DECRYPT_MODE), secretKeyCaptor.capture(), ivParameterSpecCaptor.capture());
        assertThat(secretKeyCaptor.getValue(), is(notNullValue()));
        assertThat(secretKeyCaptor.getValue().getAlgorithm(), is(ALGORITHM_AES));
        assertThat(secretKeyCaptor.getValue().getEncoded(), is(aesKey));
        assertThat(ivParameterSpecCaptor.getValue(), is(notNullValue()));
        assertThat(ivParameterSpecCaptor.getValue().getIV(), is(encodedIv.getBytes()));

        assertThat(decrypted, is(decryptedData));
    }


    //Helper methods
    private KeyPairGeneratorSpec.Builder newKeyPairGeneratorSpecBuilder(KeyPairGeneratorSpec expectedBuilderOutput) {
        KeyPairGeneratorSpec.Builder builder = PowerMockito.mock(KeyPairGeneratorSpec.Builder.class);
        PowerMockito.when(builder.setAlias(anyString())).thenReturn(builder);
        PowerMockito.when(builder.setSubject(any(X500Principal.class))).thenReturn(builder);
        PowerMockito.when(builder.setKeySize(anyInt())).thenReturn(builder);
        PowerMockito.when(builder.setSerialNumber(any(BigInteger.class))).thenReturn(builder);
        PowerMockito.when(builder.setStartDate(any(Date.class))).thenReturn(builder);
        PowerMockito.when(builder.setEndDate(any(Date.class))).thenReturn(builder);
        PowerMockito.when(builder.setEncryptionRequired()).thenReturn(builder);
        PowerMockito.when(builder.build()).thenReturn(expectedBuilderOutput);
        return builder;
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    private KeyGenParameterSpec.Builder newKeyGenParameterSpecBuilder(KeyGenParameterSpec expectedBuilderOutput) {
        KeyGenParameterSpec.Builder builder = PowerMockito.mock(KeyGenParameterSpec.Builder.class);
        PowerMockito.when(builder.setKeySize(anyInt())).thenReturn(builder);
        PowerMockito.when(builder.setCertificateSubject(any(X500Principal.class))).thenReturn(builder);
        PowerMockito.when(builder.setCertificateSerialNumber(any(BigInteger.class))).thenReturn(builder);
        PowerMockito.when(builder.setCertificateNotBefore(any(Date.class))).thenReturn(builder);
        PowerMockito.when(builder.setCertificateNotAfter(any(Date.class))).thenReturn(builder);
        //noinspection WrongConstant
        PowerMockito.when(builder.setEncryptionPaddings(anyString())).thenReturn(builder);
        //noinspection WrongConstant
        PowerMockito.when(builder.setBlockModes(anyString())).thenReturn(builder);
        PowerMockito.when(builder.build()).thenReturn(expectedBuilderOutput);
        return builder;
    }

    private CryptoUtil newCryptoUtilSpy() throws Exception {
        CryptoUtil cryptoUtil = PowerMockito.spy(new CryptoUtil(context, storage, KEY_ALIAS));
        PowerMockito.mockStatic(KeyStore.class);
        PowerMockito.when(KeyStore.getInstance(ANDROID_KEY_STORE)).thenReturn(keyStore);
        PowerMockito.mockStatic(KeyPairGenerator.class);
        PowerMockito.when(KeyPairGenerator.getInstance(ALGORITHM_RSA, ANDROID_KEY_STORE)).thenReturn(keyPairGenerator);
        PowerMockito.mockStatic(KeyGenerator.class);
        PowerMockito.when(KeyGenerator.getInstance(ALGORITHM_AES, ANDROID_KEY_STORE)).thenReturn(keyGenerator);
        PowerMockito.mockStatic(Cipher.class);
        PowerMockito.when(Cipher.getInstance(anyString())).then(new Answer<Cipher>() {
            @Override
            public Cipher answer(InvocationOnMock invocation) throws Throwable {
                String transformation = invocation.getArgumentAt(0, String.class);
                if (RSA_TRANSFORMATION.equals(transformation)) {
                    return rsaCipher;
                } else if (AES_TRANSFORMATION.equals(transformation)) {
                    return aesCipher;
                }
                return null;
            }
        });
        return cryptoUtil;
    }
}