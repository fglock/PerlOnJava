use strict;
use warnings;
use threads;
use threads::shared;

print "1..7\n";
my $number = 0;
sub check {
    my ($condition, $name) = @_;
    ++$number;
    print($condition ? "ok " : "not ok ", $number, " - ", $name, "\n");
}

my $source = { count => 1, values => [2] };

my $shared = shared_clone($source);
check(is_shared($shared), 'shared_clone marks a nested hash graph');
check(is_shared($shared->{values}), 'shared_clone marks nested arrays');
check(is_shared($shared->{count}), 'shared_clone marks nested scalar slots');

my $worker = threads->create(sub {
    lock(@{$shared->{values}});
    ++$shared->{count};
    push @{$shared->{values}}, 3;
    return join(',', @{$shared->{values}});
});
check($worker->join eq '2,3', 'child observes the nested shared graph');
check($shared->{count} == 2 && join(',', @{$shared->{values}}) eq '2,3',
    'nested shared mutations are visible to the parent');
check($source->{count} == 1 && join(',', @{$source->{values}}) eq '2',
    'shared_clone leaves the source graph isolated');

my @stress = map {
    threads->create(sub {
        for (1 .. 50) {
            lock(%$shared);
            ++$shared->{count};
        }
    });
} 1 .. 4;
$_->join for @stress;
check($shared->{count} == 202, 'nested shared scalar survives lock stress');
