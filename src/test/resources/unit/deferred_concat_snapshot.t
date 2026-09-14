package DeferredConcatTie;
sub TIESCALAR { bless { seen => $_[1] }, $_[0] }
sub FETCH { ${ $_[0]{seen} }++; return 'tied' }

package DeferredConcatHash;
sub TIEHASH { bless {}, $_[0] }
sub FETCH { return "value_$_[1]" }
sub FIRSTKEY { return undef }
sub NEXTKEY { return undef }
sub EXISTS { return 0 }
sub SCALAR { return 0 }

package main;
use strict;
use warnings;
use Test::More;

my $left = 'alpha';
my $right = 'beta';
is substr($left . ':' . $right, -6), 'a:beta',
    'snapshot reads the complete ordinary concatenation';

my $unicode = "\x{263a}";
is substr($unicode . ':tail', -5), ':tail',
    'UTF-8 concatenation keeps character offsets';

use bytes;
my $octets = "\xff";
my $octet_hex = unpack('H*', substr($octets . ':xy', -3));
is $octet_hex, '3a7879',
    'byte-string snapshot keeps octet provenance';
no bytes;

my $seen = 0;
tie my $tied, 'DeferredConcatTie', \$seen;
is substr($tied . ':x', -2), ':x', 'tied operand falls back after one FETCH';
is $seen, 1, 'tied operand fetched once';

tie my %hash, 'DeferredConcatHash';
my $key_prefix = 'key';
is $hash{$key_prefix . '_suffix'}, 'value_key_suffix',
    'a tied hash observes the complete concatenated key';

my $captured = $left . ':old';
my $alias = \$captured;
$captured = $left . ':new';
is $$alias, 'alpha:new', 'assignment materializes before an alias can observe it';

done_testing;
