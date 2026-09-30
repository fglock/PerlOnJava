use strict;
use warnings;
use Test::More;

eval q{sub { my DefinitelyMissingCoreBatchClass $value; }};
like $@, qr/No such class/, 'eval rejects an unknown typed lexical declaration';

my $filter;
eval 'grep $filter (1, 2, 3);';
like $@, qr/Missing comma after first argument to grep function/,
    'grep reports a missing comma after a scalar block';

{
    package Local::SparseReverseTie;
    sub TIEARRAY { bless { values => {}, size => 0 }, shift }
    sub FETCHSIZE { $_[0]{size} }
    sub EXISTS { exists $_[0]{values}{$_[1]} }
    sub FETCH { $_[0]{values}{$_[1]} }
    sub STORE { $_[0]{values}{$_[1]} = $_[2]; $_[0]{size} = $_[1] + 1 if $_[1] >= $_[0]{size} }
    sub DELETE { delete $_[0]{values}{$_[1]} }
    sub CLEAR { $_[0]{values} = {}; $_[0]{size} = 0 }
    sub EXTEND { }
}

tie my @sparse, 'Local::SparseReverseTie';
$sparse[0] = 'left';
$sparse[2] = 'right';
@sparse = reverse @sparse;
ok !exists $sparse[1], 'reverse assignment preserves a tied array hole';

our $map_destroy_count = 0;
{
    package Local::MapVoidTemp;
    sub new { bless {}, shift }
    sub DESTROY { $main::map_destroy_count++ }
}
my $seen_destruction = 0;
map {
    is($seen_destruction, $map_destroy_count, 'void map releases the prior result per iteration');
    $seen_destruction = $map_destroy_count + 1;
    Local::MapVoidTemp->new;
} 1 .. 3;

'abc' =~ /b/;
my $iteration = 0;
WHILE: while (1) {
    $iteration++;
    is($` . $& . $', 'abc', 'outer regex captures survive loop-control scope cleanup');
    {
        'end' =~ /end/;
        redo WHILE if $iteration == 1;
        next WHILE if $iteration == 2;
        last WHILE;
    }
}

done_testing;
