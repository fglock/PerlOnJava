use strict;
use warnings;
use Test::More;

my @modules = grep {
    my ($mod) = $_ =~ /(.+)/;
    do {
        local $SIG{__WARN__} = sub {};
        eval "require $mod";
    } ? $mod : do {
        SKIP: { skip "Failed require of $mod", 1 }
        ();
    };
} ('PerlOnJavaRegression1642::MissingOptional::Module');

is(scalar @modules, 0, 'a failed optional require is omitted from grep results');
done_testing();
