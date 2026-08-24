use strict;
use warnings;
use Test::More;

{
    package Phase36::WideString;
    use overload q{""} => sub {
        ++$main::stringifications;
        return ${$_[0]};
    };

    sub new {
        my ($class, $value) = @_;
        return bless \$value, $class;
    }
}

our $stringifications = 0;
my $list_subject = Phase36::WideString->new("a\x{100}/\x{100}b");
my @captures = ($list_subject =~ /\b(.)\x{100}/g);
is_deeply(\@captures, ['a', '/'],
    'global matching selects Unicode engine from overloaded string value');
is($stringifications, 1, 'list global match stringifies its subject once');

$stringifications = 0;
my $scalar_subject = Phase36::WideString->new("a\x{100}/\x{100}b");
ok($scalar_subject =~ /\b(.)\x{100}/g,
    'scalar global match uses overloaded Unicode value');
is($1, 'a', 'scalar global match publishes its capture');
is(pos($scalar_subject), 2,
    'global position remains attached to the original overloaded scalar');
is($stringifications, 1, 'scalar global match stringifies its subject once');

$stringifications = 0;
my $byte_subject = Phase36::WideString->new(pack('C*', 0xC3, 0xA9, 0x58));
pos($byte_subject) = 1;
$stringifications = 0;
ok($byte_subject =~ /(.)/g,
    'byte-returning overload selects the byte matcher at an existing position');
is(unpack('C', $1), 0xA9,
    'byte-returning overload publishes one byte rather than one Unicode scalar');
is(pos($byte_subject), 2,
    'byte offset is converted while pos stays on the original overloaded scalar');
is($stringifications, 1, 'byte-returning overload is stringified once');

done_testing;
