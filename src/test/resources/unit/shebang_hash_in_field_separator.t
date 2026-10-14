use strict;
use warnings;
use File::Spec;
use File::Temp qw(tempfile);
use Test::More tests => 2;

my ($script_fh, $script) = tempfile('shebang-field-separator-XXXXXX', TMPDIR => 1, UNLINK => 1);
print {$script_fh} <<'PERL' or die "write child script: $!";
#!perl -anF[~#QQ\\xq']

BEGIN {
    *ARGV = *DATA;
}
print "@F";

__DATA__
okx1x- use of alternate delimiter (lower case letter) in -F
okq2q- use of alternate delimiter (lower case letter) in -F
ok\\3\\- use of alternate delimiter (backslash) in -F
ok'4'- use of alternate delimiter (apostrophe) in -F
PERL
close $script_fh or die "close child script: $!";

my $source = do {
    open my $fh, '<', $script or die "open $script: $!";
    local $/;
    <$fh>;
};
$source =~ s/QQ/\x01\x80/;
open my $fh, '>', $script or die "open $script for update: $!";
print {$fh} $source or die "write $script: $!";
close $fh or die "close $script: $!";

my $output = qx{"$^X" "$script" 2>&1};
my $status = $? >> 8;
is($status, 0, 'alternate -F delimiters containing # run successfully');
like($output, qr/ok 4 - use of alternate delimiter \(apostrophe\) in -F\n\z/,
    'the shebang split pattern is not truncated at #');

unlink $script;
