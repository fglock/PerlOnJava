use strict;
use warnings;
use Test::More;

my $input = "literal { brace }\n";
my @warnings;
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    for (1 .. 2) {
        $input =~ s! { !OPEN!ogx;
    }
}

is($input, "literal OPEN brace }\n",
    'unescaped left brace under /x matches only the brace');
is(scalar(@warnings), 0, '/o does not repeat a warning for a valid /x literal brace');

my $escaped = "literal { brace }\n";
$escaped =~ s!\{!OPEN!ogx;
is($escaped, "literal OPEN brace }\n", 'escaped brace control remains correct');

done_testing;
