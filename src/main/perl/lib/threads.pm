package threads;

use strict;
use warnings;
our $VERSION = '2.27';

sub all ()      { 0 }
sub running ()  { 1 }
sub joinable () { 2 }

sub create {
    shift;
    return _create(@_);
}

sub new { shift->create(@_) }
sub async (&;@) { return __PACKAGE__->create(@_) }
sub self { return _self() }
sub tid { return ref($_[0]) ? $_[0]->{tid} : _self()->{tid} }
sub list { shift if @_ && !ref($_[0]) && $_[0] eq __PACKAGE__; return _list(@_) }
sub join { return _join($_[0]) }
sub detach { return _detach($_[0]) }
sub is_running { return _is_running($_[0]) }
sub is_joinable { return _is_joinable($_[0]) }
sub is_detached { return _is_detached($_[0]) }
sub error { return _error($_[0]) }
sub yield { select undef, undef, undef, 0; return }
sub equal { return defined($_[0]) && defined($_[1]) && $_[0]->tid == $_[1]->tid }
sub _stringify { return 'threads=' . $_[0]->tid }

sub import {
    my $caller = caller;
    no strict 'refs';
    *{"${caller}::async"} = \&async;
}

use overload
    '==' => 'equal',
    'eq' => 'equal',
    '""' => '_stringify',
    fallback => 1;

1;
