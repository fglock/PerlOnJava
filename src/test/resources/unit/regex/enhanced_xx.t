use strict;
use warnings;
use Test::More;

ok(eval q{ use feature 'enhanced_xx'; 1 }, q{feature 'enhanced_xx' imports});

{
    use feature 'enhanced_xx';
    no warnings 'experimental::enhanced_xx';
    my $class = qr/[ a-z  # lowercase
                      0-9 # digits
                    ]/xx;
    ok('b' =~ $class && '7' =~ $class, 'multiline class comments are active');
    ok("\n" !~ $class && "\f" !~ $class, 'literal vertical spacing is ignored');
    ok('#' !~ $class, 'comment hash is not a class member');
    ok('#' =~ /[a\#b]/xx, 'escaped hash remains literal');

    {
        no feature 'enhanced_xx';
        no warnings 'regexp';
        ok('#' =~ /[a#b]/xx, 'nested no feature restores ordinary hash semantics');
        ok("\n" =~ /[a
b]/xx, 'nested no feature restores ordinary vertical-space semantics');
    }

    ok('b' =~ /[a # comment
                 b]/xx, 'feature state is restored after nested scope');
    ok(' ' =~ /(?x:[a b])/xx, 'single inline x cancels enhanced xx parsing');
    ok(' ' =~ /(?-x:[a b])/xx, 'inline minus x cancels enhanced xx parsing');
}

{
    no feature 'enhanced_xx';
    no warnings 'regexp';
    ok('#' =~ /[a#b]/xx, 'feature remains disabled outside enabling scope');
}

sub warning_for {
    my ($source) = @_;
    my @warnings;
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    my $ok = eval $source;
    return ($ok, $@, join('', @warnings));
}

my ($hash_ok, $hash_error, $hash_warning) = warning_for(<<'SOURCE');
use feature 'enhanced_xx';
no warnings 'experimental::enhanced_xx';
use warnings 'regexp';
qr/[ ab#comment
     c]/xx;
1;
SOURCE
ok($hash_ok, 'unspaced hash warning is nonfatal');
is($hash_error, '', 'unspaced hash warning leaves no error');
like($hash_warning,
    qr/^Did you mean this to be a comment\?\nIf so, to silence this message add blanks like so: " # "\n in regex; marked by <-- HERE in m\/\[ ab# <-- HERE comment/,
    'unspaced hash warning text and position');

my ($second_ok, undef, $second_warning) = warning_for(<<'SOURCE');
use feature 'enhanced_xx';
no warnings 'experimental::enhanced_xx';
use warnings 'regexp';
qr/[ a # first # second
     b]/xx;
1;
SOURCE
ok($second_ok, 'second hash warning is nonfatal');
like($second_warning,
    qr/^Did you mean to have a second '#' in your comment\?\nIf so, escape with '\\' or quote with " or ' to silence this message\n in regex; marked by <-- HERE in m\/\[ a # first # <-- HERE /,
    'second hash warning text and position');

my ($close_ok, undef, $close_warning) = warning_for(<<'SOURCE');
use feature 'enhanced_xx';
no warnings 'experimental::enhanced_xx';
use warnings 'regexp';
qr/[ a # comment ]
     b]/xx;
1;
SOURCE
ok($close_ok, 'comment closing-bracket warning is nonfatal');
like($close_warning,
    qr/^Did you mean to have a '\]' in your comment\?\nIf so, escape with '\\' or quote with " or ' to silence this message\n in regex; marked by <-- HERE in m\/\[ a # comment \] <-- HERE /,
    'comment closing-bracket warning text and position');

done_testing;
