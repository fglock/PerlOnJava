package Crypt::Argon2;
use strict;
use warnings;
use Exporter 5.57 'import';

our $VERSION = '0.032';
our @EXPORT_OK = qw(
    argon2_raw argon2_pass argon2_verify
    argon2id_raw argon2id_pass argon2id_verify
    argon2i_raw argon2i_pass argon2i_verify
    argon2d_raw argon2d_pass argon2d_verify
    argon2_needs_rehash argon2_types argon2_implementation
);

use XSLoader;
XSLoader::load(__PACKAGE__, $VERSION);

my %multiplier = (k => 1, M => 1024, G => 1024 * 1024);
my $encoded = qr/ ^ \$ (argon2(?:i|d|id)) \$ v=(\d+) \$ m=(\d+),t=(\d+),p=(\d+) \$ ([^\$]+) \$ (.*) $ /x;

sub argon2_needs_rehash {
    my ($value, $type, $time, $memory, $parallel, $output_length, $salt_length) = @_;
    $memory =~ s/ \A (\d+) ([kMG]) \z / $1 * $multiplier{$2} * 1024 /xmse;
    $memory /= 1024;
    my ($got_type, $version, $got_memory, $got_time, $got_parallel, $salt, $hash) =
        $value =~ $encoded or return 1;
    return 1 if $got_type ne $type || $version != 19 || $got_memory != $memory
        || $got_time != $time || $got_parallel != $parallel;
    return 1 if int(3 / 4 * length($salt)) != $salt_length
        || int(3 / 4 * length($hash)) != $output_length;
    return 0;
}

sub argon2_types { qw(argon2id argon2i argon2d) }

1;
