# NOTE: Derived from blib/lib/Class/MethodMaker/hash.pm.
# Changes made here will be lost when autosplit is run again.
# See AutoSplit.pm.
package Class::MethodMaker::hash;

#line 969 "blib/lib/Class/MethodMaker/hash.pm (autosplit into blib/lib/auto/Class/MethodMaker/hash/hash0008.al)"
#------------------
# hash default_ctor

sub hash0008 {
  my $class  = shift;
  my ($target_class, $name, $options, $global) = @_;
  
  my %known_options = map {; $_ => 1 } qw( static type forward
                                           default default_ctor
                                           tie_class tie_args
                                           read_cb store_cb
                                           v1_compat );
  if ( my @bad_opt = grep ! exists $known_options{$_}, keys %$options ) {
    my $prefix = 'Option' . (@bad_opt > 1 ? 's' : '');
    croak("$prefix not recognized for attribute type hash: ",
          join(', ', @bad_opt), "\n");
  }
  
  my $type = $options->{type};
  croak "argument to -type ($type) must be a simple value\n"
    unless ! ref $type;
  
  my $forward = $options->{forward};
  my @forward;
  if ( defined $forward ) {
    if ( ref $forward ) {
      croak("-forward option can only handle arrayrefs or simple values " .
            "($forward)\n")
        unless UNIVERSAL::isa($forward, 'ARRAY');
      @forward = @$forward;
      print "Value '$_' passed to -forward is not a simple value"
        for grep ref($_), @forward;
    } else {
      @forward = $forward;
    }
  }
  
  my ($default, $dctor, $default_defined);
  if ( exists $options->{default} ) {
    croak("Cannot specify both default & default_ctor options to hash ",
          "(attribute $name\n")
      if exists $options->{default_ctor};
    $default = $options->{default};
    $default_defined = 1;
  } elsif ( exists $options->{default_ctor} ) {
    if ( ! ref $options->{default_ctor} ) {
      my $meth = $options->{default_ctor};
      croak("default_ctor can only be a simple value when -type is in effect",
            " (attribute $name)\n")
        unless defined $type;
      croak("default_ctor must be a valid identifier (or a code ref): $meth ",
            "(attribute $name)\n")
        unless $meth =~ /^[A-Za-z_][A-Za-z0-9_]*/;
      $dctor = sub { $type->$meth(@_) };
    } else {
      $dctor = $options->{default_ctor};
      croak("Argument to default_ctor must be a simple value or a code ref ",
            " (attribute $name)\n")
        if ! UNIVERSAL::isa($dctor, 'CODE');
    }
    $default_defined = 1;
  }
  
  my ($tie_class, @tie_args);
  if ( exists $options->{tie_class} ) {
    $tie_class =  $options->{tie_class};
    if ( exists $options->{tie_args} ) {
      my $tie_args =  $options->{tie_args};
      @tie_args = ref $tie_args ? @$tie_args : $tie_args;
    }
  } elsif ( exists $options->{tie_args} ) {
    carp "tie_args option ignored in absence of tie_class(attribute $name)\n";
  }
  
  # callback options
  my @read_callbacks = ref $options->{read_cb} eq 'ARRAY' ?
                        @{$options->{read_cb}}            :
                        $options->{read_cb}
    if exists $options->{read_cb};
  my @store_callbacks = ref $options->{store_cb} eq 'ARRAY' ?
                        @{$options->{store_cb}}             :
                        $options->{store_cb}
  
    if exists $options->{store_cb};
  
  
  
  # Predefine keys for subs we always want to exist (because they're
  # referenced by other subs)
  my %names = map {; $_ => undef } qw( * *_set *_reset *_index *_each );
  # The newer '*' treats a single +{} differently.  This is needed to ensure
  # that hash_init works for v1 scenarios
  $names{'='} = '*_v1compat' if $options->{v1_compat};
  
  return {
  
  
  
          '*'        =>
          sub : method {
       my $z = \@_;   # work around stack problems
            my $want = wantarray;
            print STDERR "W: ", $want, ':', join(',',@_),"\n"
              if DEBUG;
            # We also deliberately avoid instantiating storage if not
            # necessary.
            if ( @_ == 1 ) {
              if ( exists $_[0]->{$name} ) {
                return
                  unless defined $want;
                if ( $want ) {
                  %{$_[0]->{$name}};
                } else {
                  +{%{$_[0]->{$name}}};
                }
              } else {
                return
                  unless defined $want;
                if ( $want ) {
                  ();
                } else {
                  +{};
                }
              }
            } elsif ( @_ == 2 and ref $_[1] eq 'HASH') {
  
              # Only asgn-check the potential *values*
  
              if ( ! defined $want ) {
                %{$_[0]->{$name}} = %{$_[1]};
                return;
              }
  
              if ( $want ) {
                (%{$_[0]->{$name}} = %{$_[1]});
              } else {
                +{%{$_[0]->{$name}} = %{$_[1]}};
              }
            } else {
              croak "Uneven number of arguments to method '$names{'*'}'\n"
                unless @_ % 2;
  
  
              # Only asgn-check the potential *values*
  
              if ( ! defined $want ) {
                %{$_[0]->{$name}} = @_[1..$#_];
                return;
              }
  
              if ( $want ) {
                (%{$_[0]->{$name}} = @_[1..$#_]);
              } else {
                +{%{$_[0]->{$name}} = @_[1..$#_]};
              }
            }
          },
  
          #
          # This method is for internal use only.  It exists only for v1
          # compatibility, and may change or go away at any time.  Caveat
          # Emptor.
          #
  
          '!*_v1compat' =>
          sub : method {
            my $want = wantarray;
  
            if ( @_ == 1 ) {
              # No args
              return
                unless defined $want;
              $_[0]->{$name} = +{}
                unless exists $_[0]->{$name};
              return $want ? %{$_[0]->{$name}} : $_[0]->{$name};
            } elsif ( @_ == 2 ) {
              # 1 arg
              if ( my $type = ref $_[1] ) {
                if ( $type eq 'ARRAY' ) {
                  my $x = $names{'*_index'};
                  return my @x = $_[0]->$x(@{$_[1]});
                } elsif ( $type eq 'HASH' ) {
                  my $x = $names{'*_set'};
                  $_[0]->$x(%{$_[1]});
                  return $want ? %{$_[0]->{$name}} : $_[0]->{$name};
                } else {
                  # Not a recognized ref type for hash method
                  # Assume it's an object type, for use with some tied hash
                  $x = $names{'*_index'};
                  return ($_[0]->$x($_[1]))[0];
                }
              } else { # $key is simple scalar
                $x = $names{'*_index'};
                return ($_[0]->$x($_[1]))[0];
              }
            } else {
              # Many args
              unless ( @_ % 2 ) {
                carp "No value for key '$_[-1]'.";
                push @_, undef;
              }
              my $x = $names{'*_set'};
              $_[0]->$x(@_[1..$#_]);
              $x = $names{'*'};
              return $want ? %{$_[0]->{$name}} : $_[0]->{$name};
            }
          },
  
  
          '*_reset'  =>
          sub : method {
            if ( @_ == 1 ) {
  
              delete $_[0]->{$name};
            } else {
              delete @{$_[0]->{$name}}{@_[1..$#_]};
            }
            return;
          },
  
  
          '*_clear'  =>
          sub : method {
            if ( @_ == 1 ) {
              %{$_[0]->{$name}} = ();
            } else {
              ${$_[0]->{$name}}{$_} = undef
                for grep exists ${$_[0]->{$name}}{$_}, @_[1..$#_];
            }
            return;
          },
  
  
          '*_isset'  =>
          ( $default_defined      ?
            sub : method { 1 }    :
            sub : method {
              if ( @_ == 1 ) {
               exists $_[0]->{$name}
             } elsif ( @_ == 2 ) {
               exists $_[0]->{$name}->{$_[1]}
             } else {
               for ( @_[1..$#_] ) {
                 return
                   if ! exists $_[0]->{$name}->{$_};
               }
               return 1;
             }
            }
          ),
  
  
           '*_count'  =>
           sub : method {
             if ( exists $_[0]->{$name} ) {
               return scalar keys %{$_[0]->{$name}};
             } else {
               return;
             }
           },
  
  
           # I did try to do clever things with returning refs if given refs,
           # but that conflicts with the use of lvalues
           '*_index' =>
           ( $default_defined      ?
             sub : method {
               for (@_[1..$#_]) {
                 if ( ! exists ($_[0]->{$name}->{$_}) ) {
                   my $default = $dctor->($_[0]);
  
                   ($_[0]->{$name}->{$_}) = $default
                 }
  
               }
               @{$_[0]->{$name}}{@_[1..$#_]};
             }                     :
             sub : method {
               @{$_[0]->{$name}}{@_[1..$#_]};
             }
           ),
  
  
           '*_keys' =>
           sub : method {
             # Unusual ! wantarray order required because ?: supplies a scalar
             # context to it's middle argument.
             return ! wantarray ? [keys %{$_[0]->{$name}}] : keys %{$_[0]->{$name}};
           },
  
  
           '*_values' =>
           sub : method {
             # Unusual ! wantarray order required because ?: supplies a scalar
             # context to it's middle argument.
             return
               ! wantarray ? [values %{$_[0]->{$name}}] : values %{$_[0]->{$name}};
           },
  
  
           '*_each' =>
           sub : method {
             return each %{$_[0]->{$name}};
           },
  
  
           '*_exists' =>
           sub : method {
             return
               for grep ! exists  $_[0]->{$name}->{$_}, @_[1..$#_];
             return 1;
           },
  
           '*_delete' =>
           sub : method {
             if ( @_ > 1 ) {
               my $x = $names{'*_reset'};
               $_[0]->$x(@_[1..$#_]);
             }
             return;
           },
  
  
  
           '*_set'   =>
           sub : method {
             croak
               sprintf("'%s' requires an even number of args (got %d)\n",
                       $names{'*_set'}, @_-1)
               unless @_ % 2;
             if ( @_ == 3 and ref $_[1] eq 'ARRAY' ) {
  
  
               @{$_[0]->{$name}}{@{$_[1]}} = @{$_[2]};
             } else {
  
  
               ${$_[0]->{$name}}{$_[$_*2-1]} =  $_[$_*2]
                 for 1..($#_/2);
             }
             return;
           },
  
  
           '*_get'   =>
           sub : method {
             my $x = $names{'*'};
             return $_[0]->$x();
           },
  
           #
           # This method is deprecated.  It exists only for v1 compatibility,
           # and may change or go away at any time.  Caveat Emptor.
           #
  
           '!*_tally' =>
           sub : method {
             my @v;
             my ($y, $z) = @names{qw(*_set *_index)};
  
             for (@_[1..$#_]) {
               my $v = $_[0]->$z($_);
               $v++;
               $_[0]->$y($_, $v);
               push @v, $v;
             }
             return @v;
           },
  
           #
           # This method is deprecated.  It exists only for v1 compatibility,
           # and may change or go away at any time.  Caveat Emptor.
           #
  
           '!*_ref'   =>
           sub : method { $_[0]->{$name} },
  
           map({; my $f = $_;
                $_ =>
                  sub : method {
                    my $x = $names{'*'};
                    my %y = $_[0]->$x();
                    while ( my($k, $v) = each %y ) {
                      $y{$k} = $v->$f(@_[1..$#_])
                        if defined $v;
                    }
                    # Unusual ! wantarray order required because ?: supplies
                    # a scalar context to it's middle argument.
                    ! wantarray ? \%y : %y;
                  }
               } @forward),
         }, \%names;
}

# end of Class::MethodMaker::hash::hash0008
1;
