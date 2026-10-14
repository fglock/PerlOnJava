use strict;
use warnings;
use Test::More;
use Data::Dumper ();

my $dump = Data::Dumper->new([sub { 1 + 1 }])
    ->Useqq(1)->Terse(1)->Deparse(1)->Indent(0)->Dump;

like($dump, qr/\b2;\}/,
    'deparsed closure reflects the folded constant expression');
unlike($dump, qr/1\s*\+\s*1/,
    'deparsed closure does not expose an expression removed by Perl optimization');

done_testing;
