use strict;
use warnings;
use Test::More tests => 4;
use utf8;
use Encode qw(is_utf8);

my $html = '<a href="http://blah.com/ø" class="û">';
my ($quoted_attribute, $attribute);
{
    use bytes;
    if ($html =~ /\G(<a)(\s+)(href=)(["'][^"']*["'])/gc) {
        ok(!is_utf8($4), 'outer byte-mode capture is unflagged');
        if (defined($4) && $4 =~ /^([`"']?)(.*)\1$/s) {
            $quoted_attribute = $1;
            $attribute = $2;
        }
    }
    ok(!is_utf8($attribute), 'nested capture through $4 stays unflagged');
    Encode::_utf8_on($attribute);
}

is($quoted_attribute, '"', 'nested regex retains the attribute quote');
is($attribute, 'http://blah.com/ø', 'nested capture reflags to the original Unicode value');
