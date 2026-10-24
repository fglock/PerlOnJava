use strict;
use warnings;
use lib 'src/test/resources/unit/lib';
use Issue1646::PromiseProbe;
use Test::More;

my (@warnings, @errors);
{
    local $SIG{__WARN__} = sub { push @warnings, shift };

    my $promise = Issue1646::PromiseProbe->new;
    $promise->catch(sub { push @errors, @_ });
    $promise->then(sub { });
    $promise->reject('branches2');
    $promise->wait;

    is_deeply(\@errors, ['branches2'], 'handled branch receives the rejection');
    like($warnings[0] // '', qr/Unhandled rejected promise: branches2/,
        'the unhandled branch warns when its callback chain is released');
    is($warnings[1], undef, 'the branch warns only once');
}

done_testing;
