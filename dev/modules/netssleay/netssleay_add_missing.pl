#!/usr/bin/perl
# Extend the TSV with MISSING rows for symbols our plan expects but that
# aren't registered in NetSSLeay.java at all. Run once after the
# automated classifier to get full coverage.
use strict;
use warnings;

# List the symbols the plan explicitly calls out or that AnyEvent::TLS /
# IO::Socket::SSL / LWP::Protocol::https / typical Net::SSLeay consumers use.
# Keep this alphabetical for stable diffs.
my @expected = qw(
    ASN1_STRING_data ASN1_STRING_length ASN1_STRING_type
    ASN1_TIME_print ASN1_TIME_set_string

    BIO_new_mem_buf BIO_s_file

    BN_add_word BN_bin2bn BN_bn2dec BN_bn2hex BN_free BN_hex2bn BN_new

    CTX_add_client_CA CTX_add_session CTX_check_private_key CTX_ctrl
    CTX_get_client_CA_list CTX_get_ex_data CTX_get_mode CTX_get_options
    CTX_get_session_cache_mode CTX_get_timeout CTX_get_verify_depth
    CTX_get_verify_mode CTX_remove_session CTX_set_client_CA_list
    CTX_set_ex_data CTX_set_keylog_callback CTX_set_mode CTX_set_msg_callback
    CTX_set_post_handshake_auth CTX_set_psk_client_callback
    CTX_set_psk_server_callback CTX_set_quiet_shutdown
    CTX_set_session_cache_mode CTX_set_session_id_context CTX_set_timeout
    CTX_set_tlsext_servername_callback CTX_set_tlsext_status_cb
    CTX_set_tlsext_ticket_key_cb CTX_set_tmp_dh_callback CTX_set_tmp_ecdh
    CTX_set_tmp_rsa CTX_set_tmp_rsa_callback CTX_use_PrivateKey
    CTX_use_RSAPrivateKey CTX_use_RSAPrivateKey_file CTX_use_certificate
    CTX_use_certificate_ASN1 CTX_use_certificate_file

    EVP_DigestFinal EVP_DigestInit EVP_Digest
    EVP_MD_size EVP_PKEY_get1_DH EVP_PKEY_get1_DSA EVP_PKEY_get1_EC_KEY
    EVP_PKEY_get1_RSA

    ERR_load_BIO_strings ERR_load_ERR_strings ERR_load_SSL_strings
    ERR_peek_error ERR_print_errors_cb

    GENERAL_NAME_free

    HMAC HMAC_CTX_free HMAC_CTX_new HMAC_Final HMAC_Init HMAC_Init_ex
    HMAC_Update

    OCSP_BASICRESP_free OCSP_CERTID_free OCSP_REQUEST_free OCSP_REQUEST_new
    OCSP_RESPONSE_free OCSP_cert_to_id OCSP_request_add0_id
    OCSP_request_add1_nonce OCSP_response_create OCSP_response_get1_basic
    OCSP_response_results OCSP_response_status OCSP_response_status_str
    OCSP_response_verify

    P_ASN1_TIME_get_isotime P_ASN1_TIME_put2string P_EVP_PKEY_fromdata
    P_EVP_PKEY_todata P_PKCS12_load_file P_X509_add_extensions
    P_X509_copy_extensions P_X509_get_ext_key_usage P_X509_get_ext_usage
    P_X509_get_netscape_cert_type P_X509_get_signature_alg

    PKCS12_newpass PKCS12_parse PKCS7_sign PKCS7_verify

    RSA_free RSA_generate_key RSA_new RSA_private_decrypt
    RSA_private_encrypt RSA_public_decrypt RSA_public_encrypt RSA_sign
    RSA_size RSA_verify

    get_client_random get_finished get_keyblock_size get_peer_cert_chain
    get_peer_certificate get_pending get_rbio get_server_random
    get_session get_shared_ciphers get_verify_result get_version get_wbio

    i2d_SSL_SESSION

    p_next_proto_last_status p_next_proto_negotiated

    peek pending
    renegotiate
    sess_accept sess_accept_good sess_accept_renegotiate sess_cache_full
    sess_cb_hits sess_cb_hits_deprecated sess_connect sess_connect_good
    sess_connect_renegotiate sess_hits sess_misses sess_number sess_timeouts
    session_reused

    set_default_passwd_cb set_max_proto_version set_min_proto_version
    set_msg_callback set_post_handshake_auth set_quiet_shutdown set_rfd
    set_session set_shutdown set_tlsext_status_ocsp_resp
    set_tlsext_status_type set_tmp_dh set_tmp_rsa set_wfd

    sk_GENERAL_NAME_num sk_GENERAL_NAME_value sk_X509_num sk_X509_pop_free
    sk_X509_value sk_pop_free

    ssl_read_CRLF ssl_read_all ssl_read_until ssl_write_CRLF ssl_write_all

    use_PrivateKey use_PrivateKey_ASN1 use_PrivateKey_file use_RSAPrivateKey_file
    use_certificate use_certificate_ASN1 use_certificate_chain_file
    use_certificate_file

    want write_partial

    X509_NAME_ENTRY_get_data X509_NAME_ENTRY_get_object
    X509_NAME_add_entry_by_NID X509_NAME_cmp X509_NAME_entry_count
    X509_NAME_get_entry X509_NAME_get_index_by_NID X509_NAME_hash
    X509_NAME_new
    X509_STORE_CTX_get0_chain X509_STORE_CTX_set_error X509_STORE_add_cert
    X509_STORE_add_crl X509_STORE_load_locations X509_STORE_new
    X509_STORE_set1_param X509_STORE_set_default_paths
    X509_add_ext X509_check_issued X509_cmp X509_digest X509_free
    X509_get_ex_new_index X509_get_ext X509_get_ext_by_NID X509_get_ext_count
    X509_get_ext_d2i X509_get_notAfter X509_get_notBefore X509_get_pubkey
    X509_get_serialNumber X509_get_subjectAltNames X509_get_version
    X509_issuer_and_serial_hash X509_issuer_name_hash X509_new
    X509_pubkey_digest X509_set_issuer_name X509_set_notAfter
    X509_set_notBefore X509_set_pubkey X509_set_serialNumber
    X509_set_subject_name X509_set_version X509_sign X509_subject_name_hash
    X509_verify X509_verify_cert X509_verify_cert_error_string
);

# Rough phase lookup (mirrors netssleay_complete.md).
sub phase_for {
    my $n = shift;
    return 1  if $n =~ /^BIO_|^ERR_/;
    return 2  if $n =~ /^(CTX_|SSL_|set_|get_|new|connect|accept|read|write|shutdown|state|pending|peek|renegotiate|want|session_|sess_|use_|ssl_)/;
    return 3  if $n =~ /^(PEM_|d2i_|i2d_|PKCS12_|P_X509_add|P_X509_copy|P_PKCS12)/;
    return 4  if $n =~ /^(ASN1_|X509|NID_|sk_|GENERAL_NAME|OBJ_|P_X509|P_ASN1)/;
    return 5  if $n =~ /^(MD\d|SHA\d|RIPEMD|HMAC|EVP_Digest|EVP_MD|EVP_Cipher|EVP_get_(ciphe|diges)|RC4|RC2_)/;
    return 6  if $n =~ /^(RSA_|BN_|EVP_PKEY|RAND_)/;
    return 7  if $n =~ /^(OCSP_|CTX_sess|sess_)/;
    return 0;
}

my $tsv = "dev/modules/netssleay_symbols.tsv";
open my $fh, "<", $tsv or die "$tsv: $!";
my @lines = <$fh>;
close $fh;

my %have;
for my $l (@lines) {
    next if $l =~ /^\s*#/ || $l =~ /^\s*$/ || $l =~ /^name\t/;
    my ($n) = split /\t/, $l;
    $have{$n}++;
}

open my $out, ">>", $tsv or die $!;
my $added = 0;
for my $n (sort @expected) {
    next if $have{$n};
    my $ph = phase_for($n);
    my $notes =
        $ph == 1 ? "ERR queue / BIO memory buffer" :
        $ph == 2 ? "SSLEngine-driven handshake / ctx" :
        $ph == 3 ? "PEM/DER/PKCS12 parsing" :
        $ph == 4 ? "X509 introspection" :
        $ph == 5 ? "digest/HMAC/cipher wrappers" :
        $ph == 6 ? "RSA/BN/EVP_PKEY" :
        $ph == 7 ? "OCSP / session cache" :
        "misc";
    print $out join("\t", $n, "missing", "MISSING", $ph, $notes), "\n";
    $added++;
}
close $out;
print STDERR "added $added MISSING rows\n";
