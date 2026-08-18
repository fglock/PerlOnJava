use strict;
use warnings;
use Test::More;

our ($REGMARK, $REGERROR);
local $REGMARK;
local $REGERROR;

my $empty = eval q{ qr/(*:)/; 1 };
ok(!$empty, 'empty MARK shorthand label is rejected');
like($@, qr/(?:mandatory argument|unrecognized|unknown|group option)/i,
    'empty shorthand reports a MARK or group diagnostic');

my $unterminated = eval q{ qr/(*:unterminated/; 1 };
ok(!$unterminated, 'unterminated MARK shorthand label is rejected');
like($@, qr/(?:unterminated|not terminated|unmatched|end pattern|group option)/i,
    'unterminated shorthand reports a structural diagnostic');

ok('ac' =~ /a(*:first)b|a(*:second)c/,
    'MARK shorthand allows an ordinary match through a later branch');
is($REGMARK, 'second', 'successful branch publishes its shorthand label');
is($REGERROR, '', 'successful shorthand match clears REGERROR');

ok('ac' !~ /a(*:failed)(*FAIL)/, 'shorthand MARK path can fail');
is($REGMARK, '', 'failed shorthand match clears REGMARK');
is($REGERROR, 'failed', 'failed shorthand path publishes REGERROR');

ok('aab' =~ /a(*:after-a)b(*SKIP:after-a)(*FAIL)|b/,
    'named SKIP finds a shorthand MARK');
is($-[0], 2, 'named SKIP resumes at the shorthand MARK position');

my $single = 'A';
is($single =~ s/(*:B)A/$REGMARK/, 1,
    'shorthand MARK is visible to a substitution replacement');
is($single, 'B', 'single substitution uses the shorthand label');

my $global = 'CCCCBAA';
is($global =~ s/(*:X)A+|(*:Y)B+|(*:Z)C+/$REGMARK/g, 3,
    'global substitution selects three shorthand branches');
is($global, 'ZYX', 'global substitution publishes each shorthand label');

my $long = 'CCCCBAA';
is($long =~ s/(*:X)A+|(*:YYYYYYYYYYYYYYYY)B+|(*:Z)C+/$REGMARK/g, 3,
    'global substitution accepts a long shorthand label');
is($long, 'ZYYYYYYYYYYYYYYYYX',
    'long shorthand label survives replacement exactly');

our @callback_marks;
ok('foo' =~ /foo(*:callback)(?{ push @callback_marks, $REGMARK })/,
    'shorthand MARK remains visible inside a regex callback');
is_deeply(\@callback_marks, ['callback'],
    'callback observes the provisional shorthand REGMARK');

our @nested_marks;
my $inner_mark = qr/(*:inner)i/;
ok('outer' =~ /(*:outer)o(?{
        'i' =~ $inner_mark;
        push @nested_marks, $REGMARK;
    })uter/x,
    'nested regex with shorthand MARK succeeds inside a callback');
is_deeply(\@nested_marks, ['inner'],
    'nested regex publishes its own shorthand MARK during the callback');
is($REGMARK, 'outer',
    'outer shorthand MARK is restored when the outer match completes');

my $iterator = 'a';
ok($iterator =~ /a(*:once)/g, 'global shorthand iterator succeeds once');
is($REGMARK, 'once', 'successful iterator publishes its shorthand MARK');
ok(!($iterator =~ /a(*:once)/g), 'global shorthand iterator exhausts');
is($REGMARK, 'once',
    'exhausted shorthand iterator preserves the last successful REGMARK');
is($REGERROR, '', 'exhausted shorthand iterator clears REGERROR');

{
    package RegexMarkShorthandOther;
    our ($REGMARK, $REGERROR);
    local $REGMARK;
    local $REGERROR;
    ::ok('p' =~ /(*:package)p/, 'shorthand MARK works in another package');
    ::is($REGMARK, 'package',
        'shorthand REGMARK publication remains package-local');
    ::is($REGERROR, '', 'package-local shorthand success clears REGERROR');
}

done_testing;
