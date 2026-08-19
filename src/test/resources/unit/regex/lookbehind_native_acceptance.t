use strict;
use warnings;
use utf8;
no warnings 'experimental::vlb';
no warnings 'non_unicode';
use Test::More;

my $url_assertion = eval q{qr{
    (?u: (?<=[^-.]) (?=[-~.,_?\#%=&]) )
}iox};
is($@, '', 'case-insensitive negated-class lookbehind compiles');
ok('a-' =~ $url_assertion,
    'case-insensitive negated-class lookbehind matches ordinary text');

my $beyond_unicode = chr(0x110000) . '-';
ok($beyond_unicode =~ $url_assertion,
    'negated-class lookbehind consumes one encoded beyond-Unicode scalar');

my $nested = eval q{qr{
    (.*)/(.*)/(.*)\.
    (?<=(?=(?:\.(?!\d+\b)\w{1,4}$)$)\.)
    (.*)$()
}x};
is($@, '', 'nested assertion with end anchors compiles in lookbehind');
ok('my/dir/audio_07.mp3' =~ $nested,
    'nested assertion preserves the Perl path-extension match');

done_testing;
