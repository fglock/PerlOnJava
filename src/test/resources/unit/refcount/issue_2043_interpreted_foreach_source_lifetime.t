use strict;
use warnings;
use Test::More;
use Scalar::Util qw(weaken);

our $destroyed_rows = 0;

{
    package Issue2043::ForeachSourceRow;

    sub DESTROY {
        $_[0]{destroyed} = 1;
        ++$main::destroyed_rows;
    }

    sub related_row {
        die "relationship accessed after DESTROY"
            if $_[0]{destroyed};
        $_[0]{_relationship_data}{related} ||= bless {},
            'Issue2043::ForeachSourceRelated';
    }

    package Issue2043::ForeachSourceRelated;
}

sub all_rows {
    my $rows = [
        bless({}, 'Issue2043::ForeachSourceRow'),
        bless({}, 'Issue2043::ForeachSourceRow'),
    ];
    return @$rows;
}

my @weak_rows;
for my $row (all_rows()) {
    push @weak_rows, $row;
    weaken($weak_rows[-1]);

    ok(!$row->{destroyed},
        'temporary foreach source keeps a row alive after a weak observer is added');
    isa_ok($row->related_row, 'Issue2043::ForeachSourceRelated',
        'relationship cache remains usable during iteration');
}

is($destroyed_rows, 2, 'temporary foreach rows are destroyed after iteration');
is(scalar(grep { defined $_ } @weak_rows), 0,
    'weak observers clear after temporary foreach source is released');

my $destroyed_before_last = $destroyed_rows;
for my $row (all_rows()) {
    last;
}
is($destroyed_rows - $destroyed_before_last, 2,
    'last releases all rows held by the temporary foreach source');

done_testing();
