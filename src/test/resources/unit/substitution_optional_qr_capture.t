use strict;
use warnings;
use Test::More;

# Template Toolkit removes a post-chomp flag with this shape.  Keep the
# dynamic qr// capture optional: it must still consume the flag at end of
# string, rather than leaving a directive token behind.
our $chomp_flags = qr/[-=~+]/;
my $directive = '-';
my $captured;

$directive = ($directive =~ /($chomp_flags)$/o) ? $1 : '';
for ($directive) {
    s/\s*($chomp_flags)?\s*$//so;
    $captured = $1;
}

is($directive, '', 'optional qr capture is removed at end of string');
is($captured, '-', 'optional qr capture retains the consumed flag');

{
    package SubstitutionOptionalQrCapture;
    our $CHOMP_FLAGS = qr/[-=~+]/;

    sub strip_comment_chomp {
        my ($dir) = @_;
        for ($dir) {
            $dir = ($dir =~ /($CHOMP_FLAGS)$/o) ? $1 : '';
            s/\s*($CHOMP_FLAGS)?\s*$//so;
        }
        return $dir;
    }
}

is(SubstitutionOptionalQrCapture::strip_comment_chomp('-'), '',
    'comment chomp flag is removed after capture');

done_testing;
