use strict;
use warnings;
use Test::More tests => 9;

my %hash = (a => 'A', b => 'B');

is(join('|', %hash{()}), '', 'an empty key/value hash slice is valid');

my @warnings;
{
    local $SIG{__WARN__} = sub { push @warnings, "@_" };
    is(scalar eval '%hash{a}', 'A', 'scalar key/value slice returns its value');
}
like($warnings[0], qr/^%hash\{"a"\} in scalar context better written as \$hash\{"a"\}/,
    'scalar key/value slice warns with its scalar spelling');

eval 'local %hash{qw(a b)}';
like($@, qr/^Can't modify key\/value hash slice in local at/,
    'key/value hash slices cannot be localized');

eval '() = keys %hash{a}';
like($@, qr/Experimental keys on scalar is now forbidden/,
    'keys rejects a key/value hash slice');

sub kv_values :lvalue { %hash{qw(a b)} }
$_ = lc $_ for kv_values();
is_deeply(\%hash, { a => 'a', b => 'b' },
    'foreach over an lvalue key/value-slice subroutine aliases values only');

eval 'sub bad_kv :lvalue { %hash{qw(a b)} }; (bad_kv) = "x"';
like($@, qr/^Can't modify key\/value hash slice in list assignment/,
    'lvalue subroutine cannot expose a key/value slice to list assignment');

eval 'sub bad_kv_scalar :lvalue { %hash{qw(a b)} }; bad_kv_scalar() = "x"';
like($@, qr/^Can't modify key\/value hash slice in scalar assignment/,
    'lvalue subroutine cannot expose a key/value slice to scalar assignment');

sub hash_only (\%) { }
eval 'hash_only %hash{a}';
like($@, qr/^Type of arg 1 to main::hash_only must be hash \(not key\/value hash slice\) at/,
    'hash prototype rejects a key/value slice');
