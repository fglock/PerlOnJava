# NOTE: Derived from blib/lib/Class/MethodMaker/Engine.pm.
# Changes made here will be lost when autosplit is run again.
# See AutoSplit.pm.
package Class::MethodMaker::Engine;

#line 1074 "blib/lib/Class/MethodMaker/Engine.pm (autosplit into blib/lib/auto/Class/MethodMaker/Engine/_boolean.al)"
# ----------------------------------------------------------------------------

# This supplied for V1 compatiblity only

my (%BooleanPos, %BooleanFields);

sub _boolean {
  my $class = shift;
  my ($tclass, $name, $options, $global) = @_;

  check_opts([qw/ v1_compat /], $options);

  my $bstore = join '__', $tclass, 'boolean';

  $BooleanFields{$tclass} ||= [];
  my $boolean_fields = $BooleanFields{$tclass};

  my $bfp = $BooleanPos{$tclass}++;
  # $boolean_pos a global declared at top of file. We need to make a local
  # copy because it will be captured in the closure and if we capture the
  # global version the changes to it will effect all the closures. (Note also
  # that it's value is reset with each call to import_into_class.)
  push @$boolean_fields, $name;
  # $boolean_fields is also declared up above. It is used to store a list of
  # the names of all the bit fields.

  return +{
           'bits' => sub {
             my ($self, $new) = @_;
             defined $new and $self->{$bstore} = $new;
             $self->{$bstore};
           },

           'bit_fields' => sub { @$boolean_fields; },

           'bit_dump' => sub {
             my ($self) = @_;
             map { ($_, $self->$_()) } @$boolean_fields;
           },

           '*' => sub {
             my ($self, $on_off) = @_;
             defined $self->{$bstore} or $self->{$bstore} = "";
             if (defined $on_off) {
               vec($self->{$bstore}, $bfp, 1) = $on_off ? 1 : 0;
             }
             vec($self->{$bstore}, $bfp, 1);
           },

           '*_set' => sub {
             my ($self) = @_;
             $self->$name(1);
           },

           '*_clear' => sub {
             my ($self) = @_;
             $self->$name(0);
           },
          };
}

1;
# end of Class::MethodMaker::Engine::_boolean
