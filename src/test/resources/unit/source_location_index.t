use strict;
use warnings;
use utf8;
use Test::More tests => 5;

sub error_for {
    my ($padding, $line, $file, $message) = @_;
    my $source = ("\n" x $padding)
        . "use utf8;\n"
        . qq{#line $line "$file"\n}
        . qq{die "$message";\n};
    eval $source;
    return $@;
}

my $first = error_for(4_000, 700, 'same-alpha.pl', 'caf\\x{00e9}');
is($first, "caf\x{00e9} at same-alpha.pl line 700.\n",
    'blank-heavy multibyte source preserves logical location');

my $equal = error_for(4_000, 700, 'same-alpha.pl', 'caf\\x{00e9}');
is($equal, $first, 'equal source identity repeats the exact diagnostic');

my $distinct = error_for(4_000, 701, 'other-beta.pl', 'caf\\x{00e9}');
is($distinct, "caf\x{00e9} at other-beta.pl line 701.\n",
    'distinct source identity retains its own file and line');

like(error_for(8_000, 9, 'deep.pl', 'deep'),
    qr/^deep at deep\.pl line 9\.\n\z/,
    'larger padding does not alter mapped line');
is(error_for(4_000, 700, 'same-alpha.pl', 'again'),
    "again at same-alpha.pl line 700.\n",
    'repeated lookup keeps logical source identity stable');
