# NOTE: Derived from blib/lib/Class/MethodMaker/array.pm.
# Changes made here will be lost when autosplit is run again.
# See AutoSplit.pm.
package Class::MethodMaker::array;

#line 196 "blib/lib/Class/MethodMaker/array.pm (autosplit into blib/lib/auto/Class/MethodMaker/array/arra0000.al)"
#------------------
# array 

sub arra0000 {
  my $SENTINEL_CLEAR = \1;
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
    croak("Cannot specify both default & default_ctor options to array ",
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
  my %names = map {; $_ => undef } qw( * *_reset *_index );
  
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
                if ( ! defined $want ) {
                  return;
                } elsif ( $want ) {
                  return @{$_[0]->{$name}};
                } else {
                  return [@{$_[0]->{$name}}];
                }
              } else {
                if ( ! defined $want ) {
                  return;
                } elsif ( $want ) {
                  return ();
                } else {
                  return [];
                }
              }
            } else {
              {
                no warnings "numeric";
                $#_ = 0
                  if $#_ and defined $_[1] and $_[1] == $SENTINEL_CLEAR;
              }
  
              my @x;
  
  
  
              @x = @_[1..$#_];
  
  
  
  
              if ( ! defined $want ) {
                @{$_[0]->{$name}} = @x;
                return;
              } elsif ( $want ) {
                @{$_[0]->{$name}} = @x;
              } else {
                [@{$_[0]->{$name}} = @x];
              }
            }
          },
  
  
          '*_reset'  =>
          sub : method {
            if ( @_ == 1 ) {
  
              delete $_[0]->{$name};
            } else {
              delete @{$_[0]->{$name}}[@_[1..$#_]];
            }
            return;
          },
  
  
  
          '*_clear'  =>
           sub : method {
             my $x = $names{'*'};
             $_[0]->$x($SENTINEL_CLEAR);
             return;
           },
  
  
          '*_isset'  =>
          ( $default_defined      ?
            sub : method { 1 }    :
            sub : method {
              if ( @_ == 1 ) {
               exists $_[0]->{$name}
             } elsif ( @_ == 2 ) {
               exists $_[0]->{$name}->[$_[1]]
             } else {
               return
                 for grep ! exists $_[0]->{$name}->[$_], @_[1..$#_];
               return 1;
             }
            }
          ),
  
  
           '*_count'  =>
           sub : method {
             if ( exists $_[0]->{$name} ) {
               return scalar @{$_[0]->{$name}};
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
  
               }
               @{$_[0]->{$name}}[@_[1..$#_]];
             }                     :
             sub : method {
               @{$_[0]->{$name}}[@_[1..$#_]];
             }
           ),
  
  
           '*_push' =>
           sub : method {
  
             push @{$_[0]->{$name}}, @_[1..$#_];
             return;
           },
  
  
           '*_pop' =>
           sub : method {
             if ( @_ == 1 ) {
               pop @{$_[0]->{$name}};
             } else {
               return
                 unless defined wantarray;
               ! wantarray ? [splice @{$_[0]->{$name}}, -$_[1]] :
                              splice @{$_[0]->{$name}}, -$_[1] ;
             }
           },
  
  
           '*_unshift' =>
           sub : method {
  
             unshift @{$_[0]->{$name}}, @_[1..$#_];
             return;
           },
  
  
           '*_shift' =>
           sub : method {
             if ( @_ == 1 ) {
               shift @{$_[0]->{$name}};
             } else {
               splice @{$_[0]->{$name}}, 0, $_[1], return
                 unless defined wantarray;
               ! wantarray ? [splice @{$_[0]->{$name}}, 0, $_[1]] :
                              splice @{$_[0]->{$name}}, 0, $_[1] ;
             }
           },
  
  
           '*_splice' =>
           sub : method {
             # Disturbing weirdness due to prototype of splice.
             #   splice @{$_[0]->{$name}}, @_[1..$#_]
             # doesn't work because the prototype wants a scalar for
             # argument 2, so the @_[1..$#_] gets evaluated in a scalar
             # context, thus counts the elements of @_ (subtract 1).
             # Ripping of the head elements
             #   splice @{$_[0]->{$name}}, $_[1], $_[2], @_[3..$#_]
             # almost works, but that the $_[2] if not present presents an
             # undef, which works as a zero, whereas
             #   splice @{$_[0]->{$name}}, $_[1]
             # splices to the end of the array
  
             if ( @_ < 3 ) {
               if ( @_ < 2 ) {
                 $_[1] = 0;
               }
               $_[2] = @{$_[0]->{$name}} - $_[1]
             }
  
  
             splice(@{$_[0]->{$name}}, $_[1], $_[2], @_[3..$#_]), return
               unless defined wantarray;
             ! wantarray ? [splice(@{$_[0]->{$name}}, $_[1], $_[2], @_[3..$#_])] :
                            splice(@{$_[0]->{$name}}, $_[1], $_[2], @_[3..$#_])  ;
           },
  
  
           '!*_get'   =>
           sub : method {
             my $x = $names{'*'};
             return $_[0]->$x();
           },
  
  
           '*_set'   =>
           sub : method {
             if ( @_ == 3 and ref $_[1] eq 'ARRAY' ) {
  
               @{$_[0]->{$name}}[@{$_[1]}] = @{$_[2]};
             } else {
               croak
                 sprintf("'%s' requires an even number of args (got %d)\n",
                         $names{'*_set'}, @_-1)
                 unless @_ % 2;
  
               ${$_[0]->{$name}}[$_[$_*2-1]] = $_[$_*2]
                 for 1..($#_/2);
             }
             return;
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
                    my @x;
                    my @y = $_[0]->$x();
                    @x = map +(defined $_ ? $_->$f(@_[1..$#_]) : undef), @y;
                    # We don't check for a undefined wantarray here, since
                    # calling this in a void context is a sufficiently
                    # nonsensical thing to do that checking for it is likely
                    # performance hit than the typical saving.
                    ! wantarray ? \@x : @x;
                  }
               } @forward),
         }, \%names;
}

# end of Class::MethodMaker::array::arra0000
1;
