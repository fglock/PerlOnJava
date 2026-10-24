use strict;
use warnings;
use Scalar::Util qw(weaken);
use Test::More;

plan skip_all => 'Mojolicious is not installed' unless eval { require Mojo::Promise; 1 };

my (@warnings, @errors);
{
    local $SIG{__WARN__} = sub { push @warnings, shift };

    Mojo::Promise->reject('one');
    like($warnings[0] // '', qr/Unhandled rejected promise: one/,
        'a discarded rejected promise warns immediately');
    is($warnings[1], undef, 'the discarded promise warns only once');

    @warnings = ();
    Mojo::Promise->reject('two')->then(sub { })->wait;
    like($warnings[0] // '', qr/Unhandled rejected promise: two/,
        'a waited rejection warns when its child is released');
    is($warnings[1], undef, 'the waited rejection warns only once');

    @warnings = ();

    my $promise = Mojo::Promise->new;
    $promise->catch(sub { push @errors, @_ });
    my $unhandled_branch = $promise->then(sub { });
    weaken($unhandled_branch);
    $promise->reject('branches2');
    $promise->wait;
    is_deeply(\@errors, ['branches2'], 'handled branch receives the rejection');
    ok(!defined $unhandled_branch, 'unhandled child branch is released after wait');
    like($warnings[0] // '', qr/Unhandled rejected promise: branches2/,
        'unhandled branch warns when its callback chain is released');
    is($warnings[1], undef, 'unhandled rejection warns only once');
}

done_testing;
