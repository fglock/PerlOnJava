use strict;
use warnings;
use Test::More;
use Scalar::Util qw(blessed);

ok(!utf8::is_utf8(ref 1), 'ref of a non-reference is a byte string');
ok(!utf8::is_utf8(ref []), 'built-in reference type is a byte string');

my $object = bless {}, 'TestApp::Controller::Action::Path';
my $class = ref $object;
ok(!utf8::is_utf8($class), 'ASCII blessed class name is a byte string');
my $blessed_class = blessed $object;
ok(!utf8::is_utf8($blessed_class),
    'Scalar::Util::blessed returns an ASCII class name as a byte string');

my $prefix = lc $class;
$prefix =~ s/^testapp::controller:://;
$prefix =~ s/::/\//g;
my $raw_utf8 = pack('C*', 0xC3, 0xA5, 0xC3, 0xA4, 0xC3, 0xB6);
my $path = join '/', $prefix, $raw_utf8;
ok(!utf8::is_utf8($path), 'joining ref-derived prefix preserves byte semantics');
is(unpack('H*', $path),
    '616374696f6e2f706174682fc3a5c3a4c3b6',
    'ref-derived path retains the original UTF-8 octets');

my $blessed_prefix = lc $blessed_class;
$blessed_prefix =~ s/^testapp::controller:://;
$blessed_prefix =~ s/::/\//g;
my $blessed_path = join '/', $blessed_prefix, $raw_utf8;
ok(!utf8::is_utf8($blessed_path),
    'joining blessed-derived prefix preserves byte semantics');
is(unpack('H*', $blessed_path),
    '616374696f6e2f706174682fc3a5c3a4c3b6',
    'blessed-derived path retains the original UTF-8 octets');

my $component = 'TestApp::Controller::Action::Path';
if ($component =~ /^.+?::([MVC]|Model|View|Controller)::(.+)$/) {
    ok(!utf8::is_utf8($2),
        'regex capture from a byte string remains a byte string');
    my $captured_prefix = lc $2;
    $captured_prefix =~ s{::}{/}g;
    ok(!utf8::is_utf8($captured_prefix),
        'lowercasing a byte regex capture preserves byte semantics');
    my $captured_path = join '/', $captured_prefix, $raw_utf8;
    ok(!utf8::is_utf8($captured_path),
        'joining a capture-derived prefix preserves byte semantics');
    is(unpack('H*', $captured_path),
        '616374696f6e2f706174682fc3a5c3a4c3b6',
        'capture-derived path retains the original UTF-8 octets');
} else {
    fail('component class matched the expected shape') for 1 .. 4;
}

my $unicode_class;
{
    use utf8;
    $unicode_class = ref bless {}, "Ångström";
}
ok(utf8::is_utf8($unicode_class),
    'Unicode blessed class name retains its UTF-8 flag');
ok(utf8::is_utf8(blessed bless {}, $unicode_class),
    'blessed retains the UTF-8 flag on a Unicode class name');

done_testing;
