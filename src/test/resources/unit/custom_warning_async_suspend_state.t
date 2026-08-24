use strict;
use warnings;
use Test::More;

BEGIN {
    my $available = eval {
        require Future;
        require Future::AsyncAwait;
        Future::AsyncAwait->import;
        1;
    };
    plan skip_all => 'Future and Future::AsyncAwait are required for suspend coverage'
        unless $available;
}

{
    package AsyncWarningCategory;
    use warnings::register;

    sub emit_warning {
        warnings::warnif('async warning category payload');
    }
}

{
    no warnings 'AsyncWarningCategory';
    async sub suppressed_after_await {
        my ($gate) = @_;
        await $gate;
        AsyncWarningCategory::emit_warning();
        return 1;
    }
}

async sub enabled_after_await {
    my ($gate) = @_;
    await $gate;
    AsyncWarningCategory::emit_warning();
    return 1;
}

my $gate = Future->new;
my (@suppressed, @enabled);
my ($suppressed_future, $enabled_future);
{
    local $SIG{__WARN__} = sub {
        my ($warning) = @_;
        if ($warning =~ /async warning category payload/) {
            push @enabled, $warning;
        }
        else {
            push @suppressed, $warning;
        }
    };
    $suppressed_future = suppressed_after_await($gate);
    $enabled_future = enabled_after_await($gate);
    ok(!$suppressed_future->is_ready && !$enabled_future->is_ready,
        'both async frames suspended on the pending future');
    $gate->done(1);
    is($suppressed_future->get, 1, 'suppressed async frame resumed successfully');
    is($enabled_future->get, 1, 'enabled async frame resumed successfully');
}

is(scalar @enabled, 1, 'resumed enabled frame emitted one category warning');
like($enabled[0] // '', qr/^async warning category payload/,
    'resumed warning retains its payload');
is_deeply(\@suppressed, [], 'resumed suppressed frame leaked no other warning');

done_testing;
