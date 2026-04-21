#!/usr/bin/perl
use strict;
use warnings;

# Read the NetSSLeay source and classify each registered symbol.
my $src_path = "src/main/java/org/perlonjava/runtime/perlmodule/NetSSLeay.java";
open my $fh, "<", $src_path or die "$src_path: $!";
my @lines = <$fh>;
close $fh;

my %entries; # name → { kind, impl_hint, phase }

# Constants (kind=constant)
for my $i (0..$#lines) {
    next unless $lines[$i] =~ /CONSTANTS\.put\("([A-Za-z_][A-Za-z_0-9]*)"/;
    $entries{$1} //= { kind => "constant", impl => "DONE", phase => "0", notes => "" };
}

# Methods via registerMethod: may point at a Java method name (implemented) or null (auto-resolve / stub)
for my $i (0..$#lines) {
    next unless $lines[$i] =~ /mod\.registerMethod\("([A-Za-z_][A-Za-z_0-9]*)",\s*(null|"([A-Za-z_][A-Za-z_0-9]*)")/;
    my ($name, $rawTarget, $target) = ($1, $2, $3);
    next if exists $entries{$name};
    my $impl = defined $target && $target ne "" ? "DONE" : "PARTIAL";
    $entries{$name} = { kind => "method", impl => $impl, phase => "2",
                        notes => defined $target ? "→ Java $target" : "autoload dispatch" };
}

# Lambdas: walk the body and try to decide whether it's a stub or real.
# Heuristic: if the body touches HANDLE_COUNTER only + returns a random number, it's a stub;
# if it inspects / modifies state on SslCtxState / SslState / X509 classes, it's real.
for my $i (0..$#lines) {
    next unless $lines[$i] =~ /registerLambda\("([A-Za-z_][A-Za-z_0-9]*)"/;
    my $name = $1;
    next if exists $entries{$name};
    # Collect up to 15 lines or until the closing });
    my $body = "";
    for my $j ($i..$i+30) {
        last if $j > $#lines;
        $body .= $lines[$j];
        last if $lines[$j] =~ /^\s*\}\);/;
    }

    my $impl;
    my $notes;
    # Clearly-fake: returns a hardcoded success with no side effect beyond storing a field
    if ($body =~ /return new RuntimeScalar\(1\)\.getList\(\);\s*\}\);/ && $body !~ /SSL_HANDLES|CTX_HANDLES|X509_HANDLES|BIO_HANDLES|EVP|engine|wrap|unwrap/) {
        $impl = "STUB";
        $notes = "returns 1 unconditionally";
    } elsif ($body =~ /return new RuntimeScalar\(\)\.getList\(\);\s*\}\);/) {
        $impl = "STUB";
        $notes = "returns undef unconditionally";
    } elsif ($body =~ /HANDLE_COUNTER\.getAndIncrement/) {
        # Could be real handle creation or fake handle
        if ($body =~ /SSL_HANDLES|CTX_HANDLES|X509_HANDLES|BIO_HANDLES|EVP_PKEY_HANDLES|CRL_HANDLES/) {
            $impl = "DONE";
            $notes = "allocates opaque handle";
        } else {
            $impl = "STUB";
            $notes = "returns fresh handle ID but nothing behind it";
        }
    } elsif ($body =~ /SSL_HANDLES|CTX_HANDLES|X509_HANDLES|BIO_HANDLES|EVP_PKEY_HANDLES|CRL_HANDLES/) {
        $impl = "PARTIAL";
        $notes = "touches handle state";
    } else {
        $impl = "PARTIAL";
        $notes = "lambda body, check by hand";
    }

    $entries{$name} = { kind => "lambda", impl => $impl, phase => "?",
                        notes => $notes };
}

# Categorize by name prefix for phase assignment
for my $name (keys %entries) {
    my $e = $entries{$name};
    next unless $e->{phase} eq "?";
    my $p =
        $name =~ /^BIO_/          ? "1" :
        $name =~ /^ERR_/          ? "1" :
        $name =~ /^(CTX_|SSL_|set_|get_|new|connect|accept|read|write|shutdown|state|pending|peek|renegotiate|want|session_|sess_|do_https|get_https|post_https|put_https|put_http|get_http|post_http|make_form|make_headers|ssl_)/ ? "2" :
        $name =~ /^PEM_|^d2i_|^i2d_|^PKCS12_|^P_X509_add/ ? "3" :
        $name =~ /^(ASN1_|X509|NID_|sk_|GENERAL_NAME|OBJ_)/ ? "4" :
        $name =~ /^(MD\d|SHA\d|RIPEMD|HMAC|EVP_Digest|EVP_MD|EVP_Cipher|EVP_get_cipherbyname|EVP_get_digestbyname|RC4|RC2_)/ ? "5" :
        $name =~ /^(RSA_|BN_|EVP_PKEY|RAND_)/ ? "6" :
        $name =~ /^OCSP_/           ? "7" :
        $name =~ /^(CTX_sess|sess_)/ ? "7" :
        $name =~ /^(SSLeay|hello|library_init|load_error_strings|randomize|trace|die_if|die_now|initialize|constant)/ ? "0" :
        "0";
    $e->{phase} = $p;
}

# Emit TSV
print join("\t", qw(name kind impl phase notes)), "\n";
for my $name (sort keys %entries) {
    my $e = $entries{$name};
    print join("\t", $name, $e->{kind}, $e->{impl}, $e->{phase}, $e->{notes} // ""), "\n";
}
