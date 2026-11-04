use strict;
use warnings;
use Scalar::Util qw(weaken);
use Test::More tests => 3;

package Local::ReentrantOverwrite;

sub new { bless { name => $_[1] }, $_[0] }

sub DESTROY {
    my ($self) = @_;
    if ($self->{name} eq 'old') {
        $main::old_saw_replacement = ref($main::v) eq __PACKAGE__
                && $main::v->{name} eq 'new';
        Scalar::Util::weaken($main::v);
    }
    push @main::destroyed, $self->{name};
}

package main;

our (@destroyed, $old_saw_replacement, $v);
$v = Local::ReentrantOverwrite->new('old');
$v = Local::ReentrantOverwrite->new('new');

is_deeply(\@destroyed, ['old', 'new'],
    'the old value is destroyed before the replacement after reentrant weakening');
ok($old_saw_replacement,
    'the old destructor sees the already-installed replacement in the scalar');
ok(!defined($v), 'weakening the replacement during old destruction is preserved');
