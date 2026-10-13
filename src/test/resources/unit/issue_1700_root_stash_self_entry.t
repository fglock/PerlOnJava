use strict;
use warnings;
use Test::More;

package main;
sub issue_1700_named_main_sub { 'found' }

package Aspect::Pointcut;
sub find_main_named_code {
    no strict 'refs';
    my ($key, $entry);
    my $main_stash;
    while (($key, $entry) = each %{*{'::'}}) {
        next unless defined $entry;
        local (*ENTRY) = $entry;
        next unless defined *ENTRY{HASH};
        if ($key eq 'main::') {
            $main_stash = 1;
            last;
        }
    }
    return unless $main_stash;

    my @named_code;
    while (($key, $entry) = each %{*{'main::'}}) {
        next unless defined $entry;
        local (*ENTRY) = $entry;
        push @named_code, $key if defined *ENTRY{CODE};
    }
    return @named_code;
}

package main;
my @named_code = Aspect::Pointcut::find_main_named_code();
ok(grep($_ eq 'issue_1700_named_main_sub', @named_code),
    'root stash iteration reaches named subs in package main');

done_testing;
