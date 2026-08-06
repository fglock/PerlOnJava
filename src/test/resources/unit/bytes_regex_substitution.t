use strict;
use warnings;
use Test::More;

my $value = "\x{263A}";
{
    use bytes;
    $value =~ s/./X/g;
}

is($value, 'XXX', 'use bytes makes regex substitution visit each UTF-8 octet');

my $encoded = "\x{263A}";
{
    use bytes;
    $encoded =~ s/([^A-Za-z0-9\-\._~])/sprintf('%%%02X', ord($1))/gsxe;
}

is($encoded, '%E2%98%BA', 'use bytes supports octet-wise URL escaping');

my $original = "\x{263A}";
my $copy;
{
    use bytes;
    $copy = $original =~ s/./X/gr;
}

is($original, "\x{263A}", 'use bytes with substitution /r preserves the original');
is($copy, 'XXX', 'use bytes with substitution /r returns the byte-oriented result');

my %aggregate = (value => "\x{263A}");
{
    use bytes;
    $aggregate{value} =~ s/./X/g;
}

is($aggregate{value}, 'XXX', 'use bytes substitution writes through an aggregate lvalue');

{
    package Local::BytesSubstitutionTie;
    sub TIESCALAR { bless { value => $_[1] }, $_[0] }
    sub FETCH { $_[0]{value} }
    sub STORE { $_[0]{value} = $_[1] }
}

tie my $tied, 'Local::BytesSubstitutionTie', "\x{263A}";
{
    use bytes;
    $tied =~ s/./X/g;
}

is($tied, 'XXX', 'use bytes substitution writes through a tied scalar');

done_testing;
