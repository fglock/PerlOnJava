use strict;
use warnings;
use Test::More;

my @start = ('A', "\x{2118}");
my @not_start = ('0', '_', "\x{301}", "\x{200C}", "\x{1F600}");
my @continue = ('A', '0', '_', "\x{301}", "\x{2118}", "\x{200C}");
my @not_continue = ("\x{1F600}");

for my $property ('XIDS', 'XIDStart', 'XID_Start', 'xid start', 'IsXID_Start') {
    my $re = eval "qr/\\p{$property}/";
    ok(defined $re, "$property compiles");
    is(scalar(grep { $_ !~ $re } @start), 0,
        "$property includes XID_Start boundaries");
    is(scalar(grep { $_ =~ $re } @not_start), 0,
        "$property excludes non-start boundaries");
}

for my $property ('XIDC', 'XID_Continue', 'xid continue', 'IsXID_Continue') {
    my $re = eval "qr/\\p{$property}/";
    ok(defined $re, "$property compiles");
    is(scalar(grep { $_ !~ $re } @continue), 0,
        "$property includes XID_Continue boundaries");
    is(scalar(grep { $_ =~ $re } @not_continue), 0,
        "$property excludes non-continue boundaries");
}

ok('A' =~ qr/\p{XID_Start=Yes}/ && '0' !~ qr/\p{XID_Start=Yes}/,
    'XID_Start true assignment');
ok('A' !~ qr/\p{XID_Start=No}/ && '0' =~ qr/\p{XID_Start=No}/,
    'XID_Start false assignment');
ok('0' =~ qr/\p{XID_Continue=Yes}/ && "\x{1F600}" !~ qr/\p{XID_Continue=Yes}/,
    'XID_Continue true assignment');
ok('0' !~ qr/\p{XID_Continue=No}/ && "\x{1F600}" =~ qr/\p{XID_Continue=No}/,
    'XID_Continue false assignment');

ok('A' !~ qr/\p{^XID_Start}/ && '0' =~ qr/\p{^XID_Start}/,
    'inner caret complements XID_Start');
ok('A' !~ qr/\P{XID_Start}/ && '0' =~ qr/\P{XID_Start}/,
    'outer P complements XID_Start');
ok('A' =~ qr/\P{^XID_Start}/ && '0' !~ qr/\P{^XID_Start}/,
    'inner caret cancels outer P');
ok('0' !~ qr/\p{^XID_Continue}/ && "\x{1F600}" =~ qr/\p{^XID_Continue}/,
    'inner caret complements XID_Continue');

SKIP: {
    my $is_perlonjava = eval {
        require PerlOnJava::Process;
        PerlOnJava::Process::_is_perlonjava_runtime();
    } || 0;
    my $unicode_version = eval {
        require Unicode::UCD;
        Unicode::UCD::UnicodeVersion();
    } // 'unknown';
    skip "standard Perl uses Unicode $unicode_version", 4
        if !$is_perlonjava && $unicode_version lt '18.0.0';

    my $u18_xid = chr(0x3FC3F);
    ok($u18_xid =~ qr/\p{XID_Start}/,
        'UCD 18 XID_Start includes U+3FC3F');
    ok($u18_xid =~ qr/\p{XID_Continue}/,
        'UCD 18 XID_Continue includes U+3FC3F');
    ok($u18_xid !~ qr/\p{XID_Start=No}/,
        'UCD 18 XID_Start false assignment excludes U+3FC3F');
    ok($u18_xid !~ qr/\p{XID_Continue=No}/,
        'UCD 18 XID_Continue false assignment excludes U+3FC3F');
}

my $bad = eval q{qr/\p{XIDCont}/; 1};
ok(!defined $bad, 'non-Perl XIDCont abbreviation is rejected');
like($@, qr{Can't find Unicode property definition "XIDCont"},
    'invalid abbreviation reports the property name');

done_testing;
